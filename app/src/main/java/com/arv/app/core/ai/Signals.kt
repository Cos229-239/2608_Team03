package com.arv.app.core.ai

import com.arv.app.core.model.Person
import com.arv.app.core.model.Story
import com.arv.app.core.model.TranscriptSegment

/** One reason a memory answers a question: what matched, how much it counts, in words. */
data class Reason(
    val kind: Kind,
    val score: Int,
    /** A whole sentence, shown under the source so a family can see why it was picked. */
    val text: String
) {
    enum class Kind(val strength: MatchStrength) {
        NARRATOR(MatchStrength.STATED),
        SUBJECT(MatchStrength.STATED),
        ERA(MatchStrength.STATED),
        PLACE(MatchStrength.STATED),
        PLACE_WORD(MatchStrength.WRITTEN),
        TITLE(MatchStrength.WRITTEN),
        TAG(MatchStrength.WRITTEN),
        SPOKEN(MatchStrength.SPOKEN)
    }

    val strength: MatchStrength get() = kind.strength
}

/**
 * Every signal a librarian scores, written once. The hive asks for them a shelf at a time and
 * the flat pipeline asks for all of them at once, so the two rank by the same rules because
 * they run the same code, not because a comment says they do.
 */
internal object Signals {

    /** "What did Ruth say" should beat any word overlap, because that is how a family asks. */
    fun person(story: Story, person: Person, parsed: QuestionParse): List<Reason> {
        if (person.personId !in parsed.personIds) return emptyList()
        return buildList {
            if (person.personId in story.narratorIds) {
                add(Reason(Reason.Kind.NARRATOR, 6, "Told by ${person.displayName}."))
            }
            if (person.personId in story.subjectPersonIds) {
                add(Reason(Reason.Kind.SUBJECT, 4, "About ${person.displayName}."))
            }
        }
    }

    /** A year in the question that falls inside the memory's era. */
    fun era(story: Story, years: List<Int>): List<Reason> {
        val start = story.eraStart ?: return emptyList()
        val end = story.eraEnd ?: start
        return years.filter { it in start..end }
            .map { Reason(Reason.Kind.ERA, 5, "Its years include $it.") }
    }

    /**
     * Where it happened. A family archive holds the same place across decades, and without
     * place the librarian could not tell Vicksburg 1953 from Vicksburg 1980.
     *
     * Named in full, the place is something the family stated about this memory, weighted
     * like its narrator. One shared word is weaker and says which word, because "house" in a
     * question about Mom's house is not a claim that the memory happened there.
     */
    fun place(story: Story, parsed: QuestionParse): Reason? {
        val place = story.placeLabel ?: return null
        if (Matching.namesPlace(parsed, place)) {
            return Reason(Reason.Kind.PLACE, 6, "Saved with the place $place.")
        }
        val word = Matching.sharedPlaceWord(parsed, place) ?: return null
        return Reason(Reason.Kind.PLACE_WORD, 4, "Its place, $place, shares the word \"$word\".")
    }

    /** Words the family wrote on the memory: its title and its tags. */
    fun written(story: Story, parsed: QuestionParse): List<Reason> {
        val title = Matching.normalize(story.title)
        val tags = story.tags.map(Matching::normalize)
        return parsed.terms.flatMap { term ->
            val word = parsed.patternFor(term)
            buildList {
                if (word.containsMatchIn(title)) {
                    add(Reason(Reason.Kind.TITLE, 3, "\"$term\" is in the title."))
                }
                if (tags.any { word.containsMatchIn(it) }) {
                    add(Reason(Reason.Kind.TAG, 2, "Tagged \"$term\"."))
                }
            }
        }
    }

    /**
     * The words inside the recording. This is what makes it retrieval rather than filename
     * search, and it is also the weakest kind of match: a word said in passing is not what a
     * memory is about, so on its own it only ever makes a memory "might be related".
     */
    suspend fun spoken(
        story: Story,
        parsed: QuestionParse,
        segmentsForStory: suspend (storyId: String) -> List<TranscriptSegment>
    ): Reason? {
        if (parsed.terms.isEmpty() || story.durationMs <= 0) return null
        val said = segmentsForStory(story.storyId).map { Matching.normalize(it.text) }
        val hits = said.sumOf { line -> parsed.terms.count { parsed.patternFor(it).containsMatchIn(line) } }
        if (hits == 0) return null
        val words = parsed.terms.filter { term -> said.any { parsed.patternFor(term).containsMatchIn(it) } }
        val quoted = words.joinToString(", ") { "\"$it\"" }
        val verb = if (words.size == 1) "is" else "are"
        return Reason(Reason.Kind.SPOKEN, minOf(hits * 2, 8), "$quoted $verb said in the recording.")
    }
}
