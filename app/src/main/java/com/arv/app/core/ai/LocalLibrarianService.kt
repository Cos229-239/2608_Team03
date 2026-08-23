package com.arv.app.core.ai

import com.arv.app.core.model.LibrarianAnswer
import com.arv.app.core.model.LibrarianScope
import com.arv.app.core.model.Person
import com.arv.app.core.model.Story
import com.arv.app.core.model.TranscriptSegment

/**
 * The real librarian: a deterministic pipeline, not a generative model.
 *
 * Parse the question, score the archive, filter by permission, and answer with the
 * family's own words. The composed text is connective tissue and is labeled as such in the
 * UI; the ground is always a verbatim quote with a timestamp. This is the "pipeline before
 * agents" decision from docs/IDEAS.md made concrete: every step is inspectable, every
 * answer is reproducible, and nothing here can invent a sentence a person never said.
 *
 * Runs entirely on device. No key, no network, no provider account, which also means the
 * public class repo never needs a secret to build.
 */
class LocalLibrarianService(
    private val storiesProvider: suspend (familyId: String) -> List<Story>,
    private val peopleProvider: suspend (familyId: String) -> List<Person>,
    private val segmentsForStory: suspend (storyId: String) -> List<TranscriptSegment>
) : LibrarianService {

    override suspend fun ask(
        question: String,
        scope: LibrarianScope,
        viewer: Viewer,
        familyId: String
    ): LibrarianOutcome {
        val all = storiesProvider(familyId)
        if (all.isEmpty()) return LibrarianOutcome.NoMatches

        val people = peopleProvider(familyId)
        val parsed = QuestionParse.of(question, people)

        // Score everything first, then filter by permission, so the withheld count is
        // honest: "it matched, and you may not read it" is real information.
        val scored = all.mapNotNull { story ->
            val score = scoreStory(story, parsed)
            if (score <= 0) null else story to score
        }
        if (scored.isEmpty()) return LibrarianOutcome.NoMatches

        val (usable, withheldCount) =
            MemoryAccess.partition(scored.map { it.first }, viewer, scope)
        if (usable.isEmpty()) return LibrarianOutcome.AllWithheld(withheldCount)

        val byScore = usable.sortedWith(
            compareByDescending<Story> { story -> scored.first { it.first == story }.second }
                .thenByDescending { it.createdAt }
        ).take(AnswerAssembly.MAX_SOURCES)

        val sources = byScore.map { story ->
            AnswerAssembly.sourceFor(story, parsed, segmentsForStory)
        }

        return LibrarianOutcome.Answered(
            LibrarianAnswer(
                question = question,
                scope = scope,
                text = AnswerAssembly.composeLead(byScore, people, withheldCount),
                sources = sources,
                withheldCount = withheldCount
            )
        )
    }

    // --- scoring ---

    private suspend fun scoreStory(story: Story, parsed: QuestionParse): Int {
        var score = 0

        // People are the strongest signal. "What did Ruth say" should beat any word
        // overlap, because that is how a family actually asks.
        if (parsed.personIds.isNotEmpty()) {
            if (story.narratorIds.any { it in parsed.personIds }) score += 6
            if (story.subjectPersonIds.any { it in parsed.personIds }) score += 4
        }

        // A year in the question lands inside the story's era.
        parsed.years.forEach { year ->
            val start = story.eraStart
            val end = story.eraEnd ?: story.eraStart
            if (start != null && end != null && year in start..end) score += 5
        }

        val title = story.title.lowercase()
        parsed.terms.forEach { term ->
            if (title.contains(term)) score += 3
            if (story.tags.any { it.lowercase().contains(term) }) score += 2
        }

        // The words inside the recording count. This is what makes it retrieval rather
        // than filename search: the archive is searched by what people actually said.
        if (parsed.terms.isNotEmpty() && story.durationMs > 0) {
            val segments = segmentsForStory(story.storyId)
            val transcriptHits = segments.sumOf { segment ->
                val text = segment.text.lowercase()
                parsed.terms.count { text.contains(it) }
            }
            score += minOf(transcriptHits * 2, 8)
        }

        return score
    }

}

/**
 * What the question is actually asking, extracted deterministically.
 *
 * No model in the loop: names come from the family's own people list, years from digits,
 * terms from what is left. This will never be as clever as an embedding, and it will never
 * hallucinate an intent either. Embeddings can arrive later as an additional signal
 * without changing anything downstream of this type.
 */
data class QuestionParse(
    val terms: List<String>,
    val personIds: Set<String>,
    val years: List<Int>
) {
    companion object {
        private val STOPWORDS = setOf(
            "the", "and", "was", "were", "what", "when", "where", "who", "why", "how",
            "did", "does", "about", "tell", "with", "that", "this", "from", "have",
            "has", "had", "her", "his", "she", "him", "they", "them", "their", "our",
            "your", "you", "for", "are", "can", "could", "would", "will", "say", "said",
            "talk", "talked", "story", "stories", "memory", "memories", "anything"
        )

        fun of(question: String, people: List<Person>): QuestionParse {
            val lower = question.lowercase()

            val years = Regex("\\b(1[89]\\d{2}|20\\d{2})\\b")
                .findAll(lower).map { it.value.toInt() }.toList()

            val personIds = people.filter { person ->
                val names = person.displayName.lowercase().split(" ") + person.alsoKnownAs.map { it.lowercase() }
                names.any { name -> name.length > 2 && lower.contains(name) }
            }.map { it.personId }.toSet()

            val matchedNameWords = people.flatMap {
                it.displayName.lowercase().split(" ") + it.alsoKnownAs.map { aka -> aka.lowercase() }
            }.toSet()

            val terms = Regex("[a-z']+").findAll(lower)
                .map { it.value }
                .filter { it.length > 2 && it !in STOPWORDS && it !in matchedNameWords }
                .distinct()
                .toList()

            return QuestionParse(terms = terms, personIds = personIds, years = years)
        }
    }
}
