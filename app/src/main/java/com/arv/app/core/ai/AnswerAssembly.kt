package com.arv.app.core.ai

import com.arv.app.core.model.LibrarianSource
import com.arv.app.core.model.Person
import com.arv.app.core.model.Provenance
import com.arv.app.core.model.Story
import com.arv.app.core.model.TranscriptSegment

/**
 * The last step of every librarian: rank what matched, turn it into grounded sources, and
 * write a plain lead line.
 *
 * Extracted so the rules live exactly once no matter which retrieval strategy ran. The
 * flat pipeline and the hive must never drift apart on what counts as a quote, how a
 * transcript is labeled, how firmly an answer may speak, or what the composed line is
 * allowed to sound like.
 */
internal object AnswerAssembly {

    const val MAX_SOURCES = 4
    const val QUOTE_MAX = 160

    /** A memory and every reason it answers the question. */
    data class Match(val story: Story, val reasons: List<Reason>) {
        val score: Int get() = reasons.sumOf { it.score }

        /** The firmest reason decides how firmly the memory answers. */
        val strength: MatchStrength get() = reasons.minOf { it.strength }

        /** The place the question named in full, when this memory is saved with it. */
        val namedPlace: String?
            get() = story.placeLabel?.takeIf { reasons.any { it.kind == Reason.Kind.PLACE } }
    }

    /**
     * Firmest first, then highest score, then newest.
     *
     * Score alone let a recording that said "house" three times outrank two memories the
     * family saved at Mom's house, because a transcript can pile up points a place never
     * could. What the family stated about a memory now always comes before words that only
     * happen to appear in it.
     */
    fun rank(matches: List<Match>): List<Match> =
        matches.sortedWith(
            compareBy<Match> { it.strength.ordinal }
                .thenByDescending { it.score }
                .thenByDescending { it.story.createdAt }
        )

    /**
     * The quote is the ground. For recordings, the best transcript line, with its
     * timestamp so the player can jump straight to the moment. For everything else,
     * the record's own title.
     *
     * The source also carries why it was picked, in words, and says so plainly when the only
     * link is something said in the recording.
     */
    suspend fun sourceFor(
        match: Match,
        parsed: QuestionParse,
        segmentsForStory: suspend (storyId: String) -> List<TranscriptSegment>
    ): LibrarianSource {
        val story = match.story
        val segments = if (story.durationMs > 0) segmentsForStory(story.storyId) else emptyList()

        fun hits(segment: TranscriptSegment): Int {
            val line = Matching.normalize(segment.text)
            return parsed.terms.count { parsed.patternFor(it).containsMatchIn(line) }
        }
        val best = segments.maxByOrNull(::hits)?.takeIf { hits(it) > 0 }

        val tentative = match.strength == MatchStrength.SPOKEN
        val why = match.reasons
            .sortedWith(compareBy<Reason> { it.strength.ordinal }.thenByDescending { it.score })
            .map { it.text }
            .distinct()
            .take(2)
            .joinToString(" ")
            .let { if (tentative) "Might be related. $it" else it }

        return if (best != null) {
            LibrarianSource(
                storyId = story.storyId,
                personId = story.narratorIds.firstOrNull(),
                quote = clip(best.text),
                startMs = best.startMs,
                // Provenance discipline on the quote itself: a machine transcript stays
                // labeled as one until a human verified that line. Then, and only then,
                // it carries the recording's own authority.
                provenance = if (best.humanVerified) story.provenance else Provenance.AI_TRANSCRIBED,
                area = story.area,
                why = why,
                tentative = tentative
            )
        } else {
            LibrarianSource(
                storyId = story.storyId,
                personId = story.narratorIds.firstOrNull(),
                quote = story.title,
                startMs = null,
                // A title is typed by whoever filed the recording. It is not the
                // narrator's voice, and it must never be labeled as one.
                //
                // Inheriting story.provenance here meant AUTHENTIC_RECORDING, which the
                // librarian renders as the chip "THEIR VOICE" over a sentence nobody
                // ever said, with no timestamp behind it. That is the single claim this
                // project promises it will never make, and it fired on the demo query,
                // because QuestionParse strips digits so "What happened in 1953" matches
                // no transcript segment and lands here.
                provenance = Provenance.HUMAN_WRITTEN,
                area = story.area,
                why = why,
                tentative = tentative
            )
        }
    }

    /**
     * The lead line is deliberately plain. It says what was found and where it points; it
     * does not narrate, interpret, or emote. Everything with a voice in the answer belongs
     * to a family member.
     *
     * It also says how firm the answer is. A place the family saved a memory with is stated
     * as the fact it is. When the only link is words said in a recording, the line says the
     * memory might be related, because presenting a guess in the same voice as a fact is how
     * a family stops trusting what the archive tells them.
     *
     * [ranked] is every usable match, in [rank] order, so the counts are true even when only
     * the first few become sources.
     */
    fun composeLead(
        ranked: List<Match>,
        people: List<Person>,
        @Suppress("UNUSED_PARAMETER") withheldCount: Int
    ): String {
        val first = ranked.first()
        val narrator = first.story.narratorIds.firstOrNull()
            ?.let { id -> people.firstOrNull { it.personId == id }?.displayName }
        val named = "\"${first.story.title}\" (${first.story.eraLabel})" +
            (narrator?.let { ", told by $it" } ?: "")

        val place = first.namedPlace
        val opening = when {
            place != null -> {
                val here = ranked.count { match ->
                    match.namedPlace?.let(Matching::normalize) == Matching.normalize(place)
                }
                if (here == 1) "One memory is saved with the place $place: $named."
                else "$here memories are saved with the place $place. The first is $named."
            }
            first.strength == MatchStrength.SPOKEN ->
                if (ranked.size == 1) {
                    "Nothing in the archive is saved under that. One recording uses words from " +
                        "the question and might be related: $named."
                } else {
                    "Nothing in the archive is saved under that. ${ranked.size} recordings use " +
                        "words from the question and might be related. The closest is $named."
                }
            ranked.size == 1 -> "One memory speaks to this: $named."
            else -> "${ranked.size} memories speak to this. The closest is $named."
        }

        return "$opening Their own words are below."
    }

    private fun clip(text: String): String =
        if (text.length <= QUOTE_MAX) text
        else text.take(QUOTE_MAX).substringBeforeLast(' ') + "…"
}
