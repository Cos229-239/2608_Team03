package com.arv.app.core.ai

import com.arv.app.core.model.ArchiveArea
import com.arv.app.core.model.LibrarianAnswer
import com.arv.app.core.model.LibrarianScope
import com.arv.app.core.model.LibrarianSource
import com.arv.app.core.model.Person
import com.arv.app.core.model.Provenance
import com.arv.app.core.model.Story

/**
 * Why an answer could not be produced. These are outcomes, not errors: "I have nothing to
 * go on" is a correct and useful thing for an archive to say.
 */
sealed class LibrarianOutcome {
    data class Answered(val answer: LibrarianAnswer) : LibrarianOutcome()

    /** Nothing matched at all. */
    data object NoMatches : LibrarianOutcome()

    /**
     * Things matched but the viewer may not use any of them. The count is safe to show;
     * the content is not.
     */
    data class AllWithheld(val withheldCount: Int) : LibrarianOutcome()

    data class Failed(val message: String) : LibrarianOutcome()
}

interface LibrarianService {
    suspend fun ask(
        question: String,
        scope: LibrarianScope,
        viewer: Viewer,
        familyId: String
    ): LibrarianOutcome
}

/**
 * Wraps any [LibrarianService] and enforces the contract from docs/PITCH.md slide 6.
 *
 * The rule that an answer must carry sources lives HERE, in a decorator, rather than in a
 * prompt or in the UI. A prompt is a request; this is a guarantee. If a future
 * implementation swaps in a different model that ignores its instructions, the answer
 * still cannot reach the screen ungrounded.
 */
class GroundingEnforcer(
    private val delegate: LibrarianService
) : LibrarianService {

    override suspend fun ask(
        question: String,
        scope: LibrarianScope,
        viewer: Viewer,
        familyId: String
    ): LibrarianOutcome {
        val outcome = delegate.ask(question, scope, viewer, familyId)
        if (outcome !is LibrarianOutcome.Answered) return outcome

        val answer = outcome.answer

        // An unsourced answer is not a degraded answer. It is a fabrication, and it does
        // not render.
        if (!answer.isGrounded) {
            return LibrarianOutcome.Failed(
                "The librarian produced an answer with no sources. It was discarded."
            )
        }

        // A source that is itself machine-written cannot be the ground for a claim about
        // what a person said. Connective text can appear in an answer, never underneath it.
        if (answer.sources.all { it.provenance == Provenance.AI_ORGANIZED }) {
            return LibrarianOutcome.Failed(
                "Every source for that answer was machine-written. It was discarded."
            )
        }

        return outcome
    }
}

/**
 * Enforces the rule that the librarian organizes documented health patterns but never
 * draws a medical conclusion from them.
 *
 * Written as a decorator for the same reason as [GroundingEnforcer]: an instruction in a
 * prompt is a request, and this needs to be a guarantee. The family health archive only
 * works if a relative can contribute a cause of death without the software turning it into
 * a prediction about their children.
 *
 * The rule is deliberately blunt. When any source is a health record, the answer presents
 * the records and attributes them, and does not reason across them. A pattern across three
 * relatives is a real thing a family should see; deciding what it means is a doctor's job.
 */
class ClinicalClaimGuard(
    private val delegate: LibrarianService
) : LibrarianService {

    override suspend fun ask(
        question: String,
        scope: LibrarianScope,
        viewer: Viewer,
        familyId: String
    ): LibrarianOutcome {
        val outcome = delegate.ask(question, scope, viewer, familyId)
        if (outcome !is LibrarianOutcome.Answered) return outcome

        val answer = outcome.answer
        val touchesHealth = answer.sources.any { it.area == ArchiveArea.HEALTH }
        if (!touchesHealth) return outcome

        if (CLINICAL_LANGUAGE.any { it.containsMatchIn(answer.text) }) {
            return LibrarianOutcome.Failed(
                "That answer drew a medical conclusion from family records. It was discarded. " +
                    "Open the health archive to read what relatives actually recorded."
            )
        }

        return LibrarianOutcome.Answered(
            answer.copy(
                text = answer.text + "\n\n" + DISCLOSURE,
                medicalRecordsPresent = true
            )
        )
    }

    private companion object {
        /**
         * Not an exhaustive filter and not meant to be. It is a backstop under a system
         * that is already forbidden to paraphrase health records at the retrieval layer
         * (see MemoryAccess.mayParaphrase). Two independent barriers, both cheap.
         */
        val CLINICAL_LANGUAGE = listOf(
            Regex("\\byou (are|may be|could be|might be) at (a )?(higher |increased |elevated )?risk\\b", RegexOption.IGNORE_CASE),
            Regex("\\byou (probably |likely )?(have|carry|inherited)\\b", RegexOption.IGNORE_CASE),
            Regex("\\b(suggests|indicates|means) (that )?(you|your family) (have|has|are|is)\\b", RegexOption.IGNORE_CASE),
            Regex("\\b(diagnos|prognos)", RegexOption.IGNORE_CASE),
            Regex("\\b(hereditary|genetic|runs in the family)\\b", RegexOption.IGNORE_CASE),
            Regex("\\bshould (get tested|see a|talk to)\\b", RegexOption.IGNORE_CASE)
        )

        const val DISCLOSURE =
            "These are records your family entered, shown as they were written. " +
                "Arv does not interpret them. Bring them to a doctor."
    }
}

/**
 * AI-9 ships this so the Interface role can build screen 21 before retrieval exists.
 *
 * It does real permission filtering against real local stories, so the withheld path and
 * the empty path are exercised for actual reasons. Only the composition step is canned.
 */
class FakeLibrarianService(
    private val storiesProvider: suspend (familyId: String) -> List<Story>,
    private val peopleProvider: suspend (familyId: String) -> List<Person> = { emptyList() }
) : LibrarianService {

    override suspend fun ask(
        question: String,
        scope: LibrarianScope,
        viewer: Viewer,
        familyId: String
    ): LibrarianOutcome {
        val all = storiesProvider(familyId)
        if (all.isEmpty()) return LibrarianOutcome.NoMatches

        // Stand-in for embedding retrieval: naive term overlap on title and tags.
        val terms = question.lowercase().split(" ").filter { it.length > 3 }
        val candidates = all.filter { story ->
            terms.any { term ->
                story.title.lowercase().contains(term) ||
                    story.tags.any { it.lowercase().contains(term) }
            }
        }
        if (candidates.isEmpty()) return LibrarianOutcome.NoMatches

        // The people list is what lets consent be checked. Dropping it here would make
        // this double quietly more permissive than the real services it stands in for.
        val people = peopleProvider(familyId)
        val (usable, withheldCount) = MemoryAccess.partition(candidates, viewer, scope, people)
        if (usable.isEmpty()) return LibrarianOutcome.AllWithheld(withheldCount)

        val sources = usable.take(4).map { story ->
            LibrarianSource(
                storyId = story.storyId,
                personId = story.narratorIds.firstOrNull(),
                quote = story.title,
                startMs = null,
                provenance = story.provenance,
                area = story.area
            )
        }

        return LibrarianOutcome.Answered(
            LibrarianAnswer(
                question = question,
                scope = scope,
                text = buildString {
                    append("Found ")
                    append(usable.size)
                    append(if (usable.size == 1) " memory" else " memories")
                    append(" that speak to this. Their own words are below.")
                },
                sources = sources,
                withheldCount = withheldCount
            )
        )
    }
}
