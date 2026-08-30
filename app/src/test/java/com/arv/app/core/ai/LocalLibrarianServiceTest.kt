package com.arv.app.core.ai

import com.arv.app.core.model.AiUsePolicy
import com.arv.app.core.model.ArchiveArea
import com.arv.app.core.model.LibrarianScope
import com.arv.app.core.model.MemberRole
import com.arv.app.core.model.Person
import com.arv.app.core.model.Provenance
import com.arv.app.core.model.Story
import com.arv.app.core.model.StoryKind
import com.arv.app.core.model.TranscriptSegment
import com.arv.app.core.model.Visibility
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pipeline under the same discipline as the permission layer: deterministic in,
 * deterministic out, no device needed. If these pass, the librarian finds by name, year,
 * and spoken words, respects every permission, and never composes without ground.
 */
class LocalLibrarianServiceTest {

    private val ruth = Person(personId = "p_ruth", displayName = "Ruth Delaney", alsoKnownAs = listOf("Nana"))
    private val ray = Person(personId = "p_ray", displayName = "Ray Delaney")

    private val levee = Story(
        storyId = "s_levee",
        title = "The night the levee broke",
        kind = StoryKind.AUDIO,
        narratorIds = listOf("p_ruth"),
        eraStart = 1953, eraEnd = 1953,
        tags = listOf("flood", "childhood"),
        durationMs = 724_000,
        visibility = Visibility.FAMILY,
        createdBy = "u_dana",
        createdAt = 2L
    )

    private val shipyard = Story(
        storyId = "s_shipyard",
        title = "Uncle Ray on the shipyard years",
        kind = StoryKind.AUDIO,
        narratorIds = listOf("p_ray"),
        eraStart = 1971, eraEnd = 1979,
        tags = listOf("work"),
        durationMs = 100_000,
        visibility = Visibility.FAMILY,
        createdBy = "u_dana",
        createdAt = 1L
    )

    private val privateStory = Story(
        storyId = "s_private",
        title = "Sunday mornings I did not go to church",
        kind = StoryKind.AUDIO,
        narratorIds = listOf("p_ray"),
        tags = listOf("faith", "sunday"),
        durationMs = 100_000,
        visibility = Visibility.PRIVATE,
        aiUsePolicy = AiUsePolicy.NONE,
        createdBy = "u_theo",
        createdAt = 3L
    )

    private val leveeSegments = listOf(
        TranscriptSegment(
            assetId = "a_levee", startMs = 12_000, endMs = 31_000,
            text = "The water came up Jackson Street before sunrise and Daddy carried us out."
        ),
        TranscriptSegment(
            assetId = "a_levee", startMs = 31_000, endMs = 52_000,
            text = "We watched the levee go from the church roof."
        )
    )

    private val owner = Viewer(userId = "u_dana", role = MemberRole.OWNER)

    private fun service(
        stories: List<Story>,
        people: List<Person> = listOf(ruth, ray),
        segments: Map<String, List<TranscriptSegment>> = mapOf("s_levee" to leveeSegments)
    ) = LocalLibrarianService(
        storiesProvider = { stories },
        peopleProvider = { people },
        segmentsForStory = { id -> segments[id].orEmpty() }
    )

    // --- retrieval ---

    @Test
    fun `finds a story by narrator name`() = runBlocking {
        val outcome = service(listOf(levee, shipyard))
            .ask("What did Ruth say about the flood?", LibrarianScope.FAMILY, owner, "fam")

        val answered = outcome as LibrarianOutcome.Answered
        assertEquals("s_levee", answered.answer.sources.first().storyId)
    }

    @Test
    fun `finds a story by year inside its era`() = runBlocking {
        val outcome = service(listOf(levee, shipyard))
            .ask("What happened in 1953?", LibrarianScope.FAMILY, owner, "fam")

        val answered = outcome as LibrarianOutcome.Answered
        assertEquals("s_levee", answered.answer.sources.first().storyId)
    }

    @Test
    fun `finds a story by words spoken inside the recording`() = runBlocking {
        // "Jackson Street" appears only in the transcript, not in any title or tag.
        val outcome = service(listOf(levee, shipyard))
            .ask("Who talked about Jackson Street?", LibrarianScope.FAMILY, owner, "fam")

        val answered = outcome as LibrarianOutcome.Answered
        val source = answered.answer.sources.first()
        assertEquals("s_levee", source.storyId)
        assertTrue(source.quote.contains("Jackson Street"))
        assertEquals(12_000L, source.startMs)
    }

    @Test
    fun `unrelated question returns NoMatches, never an invented answer`() = runBlocking {
        val outcome = service(listOf(levee, shipyard))
            .ask("Tell me about spaceships", LibrarianScope.FAMILY, owner, "fam")

        assertTrue(outcome is LibrarianOutcome.NoMatches)
    }

    // --- permissions ---

    @Test
    fun `private material is counted, never quoted`() = runBlocking {
        val outcome = service(
            stories = listOf(privateStory),
            segments = emptyMap()
        ).ask("sunday church", LibrarianScope.FAMILY, owner, "fam")

        val withheld = outcome as LibrarianOutcome.AllWithheld
        assertEquals(1, withheld.withheldCount)
    }

    @Test
    fun `withheld count rides along when some matches are usable`() = runBlocking {
        val sundayKitchen = levee.copy(
            storyId = "s_kitchen",
            title = "Sunday kitchen",
            tags = listOf("sunday"),
            durationMs = 0L
        )
        val outcome = service(listOf(sundayKitchen, privateStory), segments = emptyMap())
            .ask("sunday mornings", LibrarianScope.FAMILY, owner, "fam")

        val answered = outcome as LibrarianOutcome.Answered
        assertEquals(1, answered.answer.withheldCount)
        assertTrue(answered.answer.sources.none { it.storyId == "s_private" })
    }

    // --- grounding and provenance ---

    @Test
    fun `an answered outcome always carries sources`() = runBlocking {
        val outcome = service(listOf(levee, shipyard))
            .ask("levee", LibrarianScope.FAMILY, owner, "fam")

        val answered = outcome as LibrarianOutcome.Answered
        assertTrue(answered.answer.isGrounded)
    }

    @Test
    fun `machine transcript quotes stay labeled as transcripts until verified`() = runBlocking {
        val outcome = service(listOf(levee))
            .ask("Jackson Street", LibrarianScope.FAMILY, owner, "fam")

        val answered = outcome as LibrarianOutcome.Answered
        assertEquals(Provenance.AI_TRANSCRIBED, answered.answer.sources.first().provenance)
    }

    @Test
    fun `verified quotes carry the recording's own provenance`() = runBlocking {
        val verified = mapOf(
            "s_levee" to leveeSegments.map { it.copy(humanVerified = true) }
        )
        val outcome = service(listOf(levee), segments = verified)
            .ask("Jackson Street", LibrarianScope.FAMILY, owner, "fam")

        val answered = outcome as LibrarianOutcome.Answered
        assertEquals(Provenance.AUTHENTIC_RECORDING, answered.answer.sources.first().provenance)
    }

    // --- guard integration, wired exactly as ServiceLocator wires it ---

    @Test
    fun `health sources get the disclosure through the full guard stack`() = runBlocking {
        val healthNote = Story(
            storyId = "s_health",
            title = "What the cardiologist told Ray",
            kind = StoryKind.DOCUMENT,
            area = ArchiveArea.HEALTH,
            narratorIds = listOf("p_ray"),
            subjectPersonIds = listOf("p_ray"),
            tags = listOf("heart"),
            aiUsePolicy = AiUsePolicy.QUOTE_ONLY,
            visibility = Visibility.FAMILY,
            createdBy = "u_dana",
            createdAt = 4L
        )
        val guarded = ClinicalClaimGuard(
            GroundingEnforcer(service(listOf(healthNote), segments = emptyMap()))
        )

        val outcome = guarded.ask("cardiologist heart", LibrarianScope.FAMILY, owner, "fam")

        val answered = outcome as LibrarianOutcome.Answered
        assertTrue(answered.answer.medicalRecordsPresent)
        assertTrue(answered.answer.text.contains("Bring them to a doctor"))
    }
}
