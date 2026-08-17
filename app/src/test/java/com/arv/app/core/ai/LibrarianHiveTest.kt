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
 * The hive under the same discipline as everything else: deterministic in, deterministic
 * out. If these pass, questions route to the right shelves, nominations blend across
 * shelves, permissions hold through the hive, and the answer names its route honestly.
 */
class LibrarianHiveTest {

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

    private val owner = Viewer(userId = "u_dana", role = MemberRole.OWNER, branchRootPersonId = null)

    private fun hive(
        stories: List<Story>,
        people: List<Person> = listOf(ruth, ray),
        segments: Map<String, List<TranscriptSegment>> = mapOf("s_levee" to leveeSegments)
    ) = LibrarianHive(
        storiesProvider = { stories },
        peopleProvider = { people },
        segmentsForStory = { id -> segments[id].orEmpty() }
    )

    // --- routing ---

    @Test
    fun `a name routes through that person's shelf`() = runBlocking {
        val outcome = hive(listOf(levee, shipyard))
            .ask("What did Ruth say about the flood?", LibrarianScope.FAMILY, owner, "fam")

        val answered = outcome as LibrarianOutcome.Answered
        assertEquals("s_levee", answered.answer.sources.first().storyId)
        assertTrue(answered.answer.routedThrough.contains("Ruth Delaney's shelf"))
    }

    @Test
    fun `a year routes through its decade's shelf`() = runBlocking {
        val outcome = hive(listOf(levee, shipyard))
            .ask("What happened in 1953?", LibrarianScope.FAMILY, owner, "fam")

        val answered = outcome as LibrarianOutcome.Answered
        assertEquals("s_levee", answered.answer.sources.first().storyId)
        assertTrue(answered.answer.routedThrough.contains("the 1950s shelf"))
    }

    @Test
    fun `spoken words route through the area shelf and keep the timestamped quote`() = runBlocking {
        // "Jackson Street" appears only in the transcript, not in any title or tag.
        val outcome = hive(listOf(levee, shipyard))
            .ask("Who talked about Jackson Street?", LibrarianScope.FAMILY, owner, "fam")

        val answered = outcome as LibrarianOutcome.Answered
        val source = answered.answer.sources.first()
        assertEquals("s_levee", source.storyId)
        assertTrue(source.quote.contains("Jackson Street"))
        assertEquals(12_000L, source.startMs)
        assertTrue(answered.answer.routedThrough.contains("the stories shelf"))
    }

    @Test
    fun `one question can activate person, era, and area shelves at once`() = runBlocking {
        val outcome = hive(listOf(levee, shipyard))
            .ask("What did Ruth say about the flood in 1953?", LibrarianScope.FAMILY, owner, "fam")

        val answered = outcome as LibrarianOutcome.Answered
        assertEquals("s_levee", answered.answer.sources.first().storyId)
        val route = answered.answer.routedThrough
        assertTrue(route.contains("Ruth Delaney's shelf"))
        assertTrue(route.contains("the 1950s shelf"))
        assertTrue(route.contains("the stories shelf"))
    }

    @Test
    fun `unrelated question activates no shelf and returns NoMatches`() = runBlocking {
        val outcome = hive(listOf(levee, shipyard))
            .ask("Tell me about spaceships", LibrarianScope.FAMILY, owner, "fam")

        assertTrue(outcome is LibrarianOutcome.NoMatches)
    }

    // --- blending ---

    @Test
    fun `nominations from several shelves add up and outrank a single-signal match`() = runBlocking {
        // Both stories mention work-adjacent terms, but only the shipyard story is Ray's
        // AND in the 1970s. Two shelves agreeing must beat one shelf alone.
        val outcome = hive(listOf(levee, shipyard))
            .ask("What was Ray doing in 1975?", LibrarianScope.FAMILY, owner, "fam")

        val answered = outcome as LibrarianOutcome.Answered
        assertEquals("s_shipyard", answered.answer.sources.first().storyId)
        assertTrue(answered.answer.routedThrough.contains("Ray Delaney's shelf"))
        assertTrue(answered.answer.routedThrough.contains("the 1970s shelf"))
    }

    // --- permissions through the hive ---

    @Test
    fun `private material is counted, never quoted, never named in the route`() = runBlocking {
        val outcome = hive(listOf(privateStory), segments = emptyMap())
            .ask("sunday church", LibrarianScope.FAMILY, owner, "fam")

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
        val outcome = hive(listOf(sundayKitchen, privateStory), segments = emptyMap())
            .ask("sunday mornings", LibrarianScope.FAMILY, owner, "fam")

        val answered = outcome as LibrarianOutcome.Answered
        assertEquals(1, answered.answer.withheldCount)
        assertTrue(answered.answer.sources.none { it.storyId == "s_private" })
    }

    // --- grounding and provenance survive the reorganization ---

    @Test
    fun `an answered outcome always carries sources`() = runBlocking {
        val outcome = hive(listOf(levee, shipyard))
            .ask("levee", LibrarianScope.FAMILY, owner, "fam")

        val answered = outcome as LibrarianOutcome.Answered
        assertTrue(answered.answer.isGrounded)
    }

    @Test
    fun `machine transcript quotes stay labeled as transcripts until verified`() = runBlocking {
        val outcome = hive(listOf(levee))
            .ask("Jackson Street", LibrarianScope.FAMILY, owner, "fam")

        val answered = outcome as LibrarianOutcome.Answered
        assertEquals(Provenance.AI_TRANSCRIBED, answered.answer.sources.first().provenance)
    }

    // --- guard integration, wired exactly as ServiceLocator wires it ---

    @Test
    fun `health answers route through the health shelf and carry the disclosure`() = runBlocking {
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
            GroundingEnforcer(hive(listOf(healthNote), segments = emptyMap()))
        )

        val outcome = guarded.ask("cardiologist heart", LibrarianScope.FAMILY, owner, "fam")

        val answered = outcome as LibrarianOutcome.Answered
        assertTrue(answered.answer.medicalRecordsPresent)
        assertTrue(answered.answer.text.contains("Bring them to a doctor"))
        assertTrue(answered.answer.routedThrough.contains("the health shelf"))
    }
}
