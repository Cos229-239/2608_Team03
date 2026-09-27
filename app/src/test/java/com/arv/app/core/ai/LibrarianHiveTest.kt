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
import org.junit.Assert.assertFalse
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

    private val owner = Viewer(userId = "u_dana", role = MemberRole.OWNER)

    private fun hive(
        stories: List<Story>,
        people: List<Person> = listOf(ruth, ray),
        segments: Map<String, List<TranscriptSegment>> = mapOf("s_levee" to leveeSegments)
    ) = LibrarianHive(
        storiesProvider = { stories },
        peopleProvider = { people },
        segmentsForStory = { id -> segments[id].orEmpty() }
    )

    /**
     * A relative who did not record the story and is not the narrator. Consent has to be
     * decided for them, not assumed.
     */
    private val cousin = Viewer(userId = "u_theo", role = MemberRole.OWNER)

    // --- consent through the hive ---

    @Test
    fun `an undecided narrator withholds the story from everyone but its creator`() = runBlocking {
        // Ruth has no consent decision on file (consentGranted defaults to false) and
        // u_theo neither recorded the levee story nor is Ruth. The screens already
        // withhold it; the hive has to reach the same answer or the librarian becomes
        // the one surface where consent does not apply.
        val outcome = hive(listOf(levee, shipyard))
            .ask("What did Ruth say about the flood?", LibrarianScope.FAMILY, cousin, "fam")

        assertTrue(
            "The hive answered from a story whose narrator has no consent decision",
            outcome is LibrarianOutcome.AllWithheld
        )
    }

    @Test
    fun `the creator still reads their own recording while consent is pending`() = runBlocking {
        // Whoever holds the recording holds the job of getting the consent, so shutting
        // them out of it would make that job impossible.
        val outcome = hive(listOf(levee, shipyard))
            .ask("What did Ruth say about the flood?", LibrarianScope.FAMILY, owner, "fam")

        val answered = outcome as LibrarianOutcome.Answered
        assertEquals("s_levee", answered.answer.sources.first().storyId)
    }

    @Test
    fun `a recorded consent decision lets the story through to the family`() = runBlocking {
        val outcome = hive(
            listOf(levee, shipyard),
            people = listOf(ruth.copy(consentGranted = true), ray)
        ).ask("What did Ruth say about the flood?", LibrarianScope.FAMILY, cousin, "fam")

        val answered = outcome as LibrarianOutcome.Answered
        assertEquals("s_levee", answered.answer.sources.first().storyId)
    }

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

    // --- places, and saying how sure an answer is (weekly tester report, 13 September) ---

    private val porch = Story(
        storyId = "s_porch",
        title = "The porch swing",
        kind = StoryKind.AUDIO,
        placeLabel = "Mom's house",
        durationMs = 60_000,
        visibility = Visibility.FAMILY,
        createdBy = "u_dana",
        createdAt = 5L
    )

    private val canning = Story(
        storyId = "s_canning",
        title = "Canning tomatoes every August",
        kind = StoryKind.PHOTO_SET,
        placeLabel = "Mom's house",
        visibility = Visibility.FAMILY,
        createdBy = "u_dana",
        createdAt = 6L
    )

    /** Saved with no place. Its recording mentions a house, which is not the same thing. */
    private val radio = Story(
        storyId = "s_radio",
        title = "The gospel station on Sunday",
        kind = StoryKind.AUDIO,
        durationMs = 90_000,
        visibility = Visibility.FAMILY,
        createdBy = "u_dana",
        createdAt = 7L
    )

    private val placeSegments = mapOf(
        "s_porch" to listOf(
            TranscriptSegment(assetId = "a_porch", startMs = 0, endMs = 9_000, text = "We sat out there every night it was warm.")
        ),
        "s_radio" to listOf(
            TranscriptSegment(assetId = "a_radio", startMs = 4_000, endMs = 15_000, text = "Sunday mornings the whole house smelled like biscuits and coffee.")
        )
    )

    @Test
    fun `a place asked about finds the memories saved there before a recording that only says its word`() = runBlocking {
        val outcome = hive(listOf(porch, canning, radio), segments = placeSegments)
            .ask("What happened in Mom's house?", LibrarianScope.FAMILY, owner, "fam")

        val answer = (outcome as LibrarianOutcome.Answered).answer
        assertEquals(setOf("s_porch", "s_canning"), answer.sources.take(2).map { it.storyId }.toSet())
        assertTrue(answer.text, answer.text.startsWith("2 memories are saved with the place Mom's house."))
        assertTrue(answer.routedThrough.contains("the Mom's house shelf"))

        answer.sources.take(2).forEach { saved ->
            assertFalse(saved.tentative)
            assertEquals("Saved with the place Mom's house.", saved.why)
        }
        val guess = answer.sources.last()
        assertEquals("s_radio", guess.storyId)
        assertTrue(guess.tentative)
        assertTrue(guess.why!!, guess.why!!.startsWith("Might be related."))
    }

    @Test
    fun `a phone keyboard's curly apostrophe asks for the same place`() = runBlocking {
        val outcome = hive(listOf(porch, canning, radio), segments = placeSegments)
            .ask("Which stories are explicitly associated with Mom\u2019s house?", LibrarianScope.FAMILY, owner, "fam")

        val answer = (outcome as LibrarianOutcome.Answered).answer
        assertEquals(setOf("s_porch", "s_canning"), answer.sources.take(2).map { it.storyId }.toSet())
    }

    @Test
    fun `a place the family saved outranks a recording with more points from words`() = runBlocking {
        val houseTitled = radio.copy(title = "The house on Sunday mornings")
        val outcome = hive(listOf(porch, houseTitled), segments = placeSegments)
            .ask("What happened in Mom's house?", LibrarianScope.FAMILY, owner, "fam")

        val answer = (outcome as LibrarianOutcome.Answered).answer
        assertEquals("s_porch", answer.sources.first().storyId)
    }

    @Test
    fun `when only a recording's words match, the answer says the memory might be related`() = runBlocking {
        val outcome = hive(listOf(radio), segments = placeSegments)
            .ask("What happened in Mom's house?", LibrarianScope.FAMILY, owner, "fam")

        val answer = (outcome as LibrarianOutcome.Answered).answer
        assertTrue(answer.text, answer.text.contains("might be related"))
        assertTrue(answer.sources.single().tentative)
    }

    @Test
    fun `a word is matched whole, so house is not found in household`() = runBlocking {
        val chores = Story(
            storyId = "s_chores",
            title = "Household chores",
            kind = StoryKind.DOCUMENT,
            visibility = Visibility.FAMILY,
            createdBy = "u_dana",
            createdAt = 8L
        )
        val outcome = hive(listOf(chores), segments = emptyMap())
            .ask("Tell me about the house", LibrarianScope.FAMILY, owner, "fam")

        assertTrue(outcome is LibrarianOutcome.NoMatches)
    }
}
