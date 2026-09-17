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
        val matched = all.mapNotNull { story ->
            reasonsFor(story, parsed, people).takeIf { it.isNotEmpty() }
                ?.let { AnswerAssembly.Match(story, it) }
        }
        if (matched.isEmpty()) return LibrarianOutcome.NoMatches

        val (usable, withheldCount) =
            MemoryAccess.partition(matched.map { it.story }, viewer, scope, people)
        if (usable.isEmpty()) return LibrarianOutcome.AllWithheld(withheldCount)

        val usableIds = usable.map { it.storyId }.toSet()
        val ranked = AnswerAssembly.rank(matched.filter { it.story.storyId in usableIds })

        val sources = ranked.take(AnswerAssembly.MAX_SOURCES).map { match ->
            AnswerAssembly.sourceFor(match, parsed, segmentsForStory)
        }

        return LibrarianOutcome.Answered(
            LibrarianAnswer(
                question = question,
                scope = scope,
                text = AnswerAssembly.composeLead(ranked, people, withheldCount),
                sources = sources,
                withheldCount = withheldCount
            )
        )
    }

    /** Every signal at once. The hive asks the same [Signals] one shelf at a time. */
    private suspend fun reasonsFor(story: Story, parsed: QuestionParse, people: List<Person>): List<Reason> =
        people.flatMap { Signals.person(story, it, parsed) } +
            Signals.era(story, parsed.years) +
            listOfNotNull(Signals.place(story, parsed)) +
            Signals.written(story, parsed) +
            listOfNotNull(Signals.spoken(story, parsed, segmentsForStory))
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
    val years: List<Int>,
    /** The whole question as [Matching.normalize] leaves it, for phrases like a place's name. */
    val text: String = ""
) {
    private val patterns = HashMap<String, Regex>()

    /** The whole-word pattern for one of [terms], built once per question. */
    fun patternFor(term: String): Regex = patterns.getOrPut(term) { Matching.wordPattern(term) }

    companion object {
        private val STOPWORDS = setOf(
            "the", "and", "was", "were", "what", "when", "where", "who", "why", "how",
            "did", "does", "about", "tell", "with", "that", "this", "from", "have",
            "has", "had", "her", "his", "she", "him", "they", "them", "their", "our",
            "your", "you", "for", "are", "can", "could", "would", "will", "say", "said",
            "talk", "talked", "story", "stories", "memory", "memories", "anything",
            // Words that ask rather than name. "What happened in Mom's house" is about the
            // house, and "happened" matched every recording where anything did.
            "happened", "happen", "happens", "which", "there", "been", "remember", "know", "its",
            // Contractions arrive without their apostrophe (see Matching.normalize).
            "whats", "didnt", "dont", "doesnt", "wasnt"
        )

        fun of(question: String, people: List<Person>): QuestionParse {
            val text = Matching.normalize(question)

            val years = Regex("\\b(1[89]\\d{2}|20\\d{2})\\b")
                .findAll(text).map { it.value.toInt() }.toList()

            // A name is a whole word, so Ray is not found in "array", and "Ruth's" still
            // finds Ruth. Another name for somebody is matched whole, the way it is written.
            val personIds = people.filter { person ->
                val names = Matching.normalize(person.displayName).split(" ") +
                    person.alsoKnownAs.map(Matching::normalize)
                names.any { name -> name.length > 2 && Matching.wordPattern(name).containsMatchIn(text) }
            }.map { it.personId }.toSet()

            val nameWords = people.flatMap { person ->
                Matching.normalize(person.displayName).split(" ") +
                    person.alsoKnownAs.map(Matching::normalize)
            }.toSet()

            val terms = text.split(" ")
                .filter { word ->
                    word.length > 2 &&
                        word.any(Char::isLetter) &&
                        word !in STOPWORDS &&
                        word !in nameWords &&
                        word.removeSuffix("s") !in nameWords
                }
                .distinct()

            return QuestionParse(terms = terms, personIds = personIds, years = years, text = text)
        }
    }
}
