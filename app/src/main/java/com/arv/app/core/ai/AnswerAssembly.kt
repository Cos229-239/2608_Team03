package com.arv.app.core.ai

import com.arv.app.core.model.LibrarianSource
import com.arv.app.core.model.Person
import com.arv.app.core.model.Provenance
import com.arv.app.core.model.Story
import com.arv.app.core.model.TranscriptSegment

/**
 * The last step of every librarian: turn chosen stories into grounded sources and a plain
 * lead line.
 *
 * Extracted so the rules live exactly once no matter which retrieval strategy ran. The
 * flat pipeline and the hive must never drift apart on what counts as a quote, how a
 * transcript is labeled, or what the composed line is allowed to sound like.
 */
internal object AnswerAssembly {

    const val MAX_SOURCES = 4
    const val QUOTE_MAX = 160

    /**
     * The quote is the ground. For recordings, the best transcript line, with its
     * timestamp so the player can jump straight to the moment. For everything else,
     * the record's own title.
     */
    suspend fun sourceFor(
        story: Story,
        parsed: QuestionParse,
        segmentsForStory: suspend (storyId: String) -> List<TranscriptSegment>
    ): LibrarianSource {
        val segments = if (story.durationMs > 0) segmentsForStory(story.storyId) else emptyList()

        val best = segments.maxByOrNull { segment ->
            val text = segment.text.lowercase()
            parsed.terms.count { text.contains(it) }
        }?.takeIf { segment ->
            parsed.terms.any { segment.text.lowercase().contains(it) }
        }

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
                area = story.area
            )
        } else {
            LibrarianSource(
                storyId = story.storyId,
                personId = story.narratorIds.firstOrNull(),
                quote = story.title,
                startMs = null,
                provenance = story.provenance,
                area = story.area
            )
        }
    }

    /**
     * The lead line is deliberately plain. It says what was found and where it points; it
     * does not narrate, interpret, or emote. Everything with a voice in the answer belongs
     * to a family member.
     */
    fun composeLead(
        stories: List<Story>,
        people: List<Person>,
        @Suppress("UNUSED_PARAMETER") withheldCount: Int
    ): String {
        val first = stories.first()
        val narrator = first.narratorIds.firstOrNull()
            ?.let { id -> people.firstOrNull { it.personId == id }?.displayName }

        val opening = when {
            stories.size == 1 && narrator != null ->
                "One memory speaks to this: \"${first.title}\" (${first.eraLabel}), told by $narrator."
            stories.size == 1 ->
                "One memory speaks to this: \"${first.title}\" (${first.eraLabel})."
            narrator != null ->
                "${stories.size} memories speak to this. The closest is \"${first.title}\" (${first.eraLabel}), told by $narrator."
            else ->
                "${stories.size} memories speak to this. The closest is \"${first.title}\" (${first.eraLabel})."
        }

        return "$opening Their own words are below."
    }

    private fun clip(text: String): String =
        if (text.length <= QUOTE_MAX) text
        else text.take(QUOTE_MAX).substringBeforeLast(' ') + "…"
}
