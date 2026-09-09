package com.arv.app.core.data

import com.arv.app.core.ai.Viewer
import com.arv.app.core.model.ConsentMethod
import com.arv.app.core.model.MemberRole
import com.arv.app.core.model.Person
import com.arv.app.core.model.Story
import com.arv.app.core.model.StoryKind
import com.arv.app.core.model.Visibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A face belongs to the person whose circle it fills, so drawing one is not a permission
 * question and [Portrait.resolve] is deliberately dumb about it.
 *
 * The permission question moved to where it actually lives: taking a photograph out of the
 * archive and making it somebody's face. A portrait shows on the feed, the people list and
 * a profile header, so choosing a private photograph as one would publish it to all three.
 * [Portrait.mayTakeFromArchive] is that gate, checked once when somebody decides, and the
 * cases below are the ones that must not get through.
 */
class PortraitTest {

    private val FAMILY = "fam_1"

    private val dana = Viewer("u_dana", MemberRole.OWNER, familyId = FAMILY)
    private val theo = Viewer("u_theo", MemberRole.CONTRIBUTOR, familyId = FAMILY)
    private val keeper = Viewer("u_keeper", MemberRole.KEEPER, familyId = FAMILY)

    private fun person(consentGranted: Boolean = true) = Person(
        personId = "p_ruth",
        displayName = "Ruth Delaney",
        consentGranted = consentGranted,
        consentDecidedAt = if (consentGranted) 1L else null,
        consentMethod = if (consentGranted) ConsentMethod.IN_PERSON else null
    )

    private fun story(
        visibility: Visibility = Visibility.FAMILY,
        createdBy: String = "u_theo",
        narrators: List<String> = emptyList()
    ) = Story(
        storyId = "s1",
        familyId = FAMILY,
        title = "A photograph of Ruth",
        kind = StoryKind.PHOTO_SET,
        visibility = visibility,
        narratorIds = narrators,
        subjectPersonIds = listOf("p_ruth"),
        createdBy = createdBy
    )

    private val onDisk: (String) -> Boolean = { true }
    private val notOnDisk: (String) -> Boolean = { false }

    // --- drawing a face, which is not a permission question ---

    @Test
    fun `a face on file is drawn`() {
        assertEquals(
            Portrait.Result.Show("/f/portraits/ruth.jpg"),
            Portrait.resolve("/f/portraits/ruth.jpg", onDisk)
        )
    }

    @Test
    fun `nobody has given them a face`() {
        assertEquals(Portrait.Result.NotSet, Portrait.resolve(null, onDisk))
    }

    @Test
    fun `a file that has gone is missing rather than unset`() {
        // Kept distinct so a vanished file reads as repairable rather than as a choice
        // nobody made.
        assertEquals(Portrait.Result.Missing, Portrait.resolve("/f/portraits/gone.jpg", notOnDisk))
    }

    @Test
    fun `every answer that is not Show is one the circle can render`() {
        val refusals = listOf(
            Portrait.resolve(null, onDisk),
            Portrait.resolve("/f/portraits/gone.jpg", notOnDisk)
        )
        assertTrue(refusals.none { it is Portrait.Result.Show })
    }

    // --- taking a face out of the archive, which is ---

    @Test
    fun `a family photograph may be taken as a face`() {
        assertTrue(Portrait.mayTakeFromArchive(story(), dana, listOf(person())))
    }

    /**
     * The one that matters. A portrait is shown to the whole family, so if this returned
     * true for a private photograph the circle would be a laundry for material the
     * permission filter withheld.
     */
    @Test
    fun `a private photograph cannot be promoted into somebody's circle`() {
        val private = story(visibility = Visibility.PRIVATE, createdBy = "u_theo")

        assertFalse(Portrait.mayTakeFromArchive(private, dana, listOf(person())))
        // Not even by a keeper, and not by the owner of the archive.
        assertFalse(Portrait.mayTakeFromArchive(private, keeper, listOf(person())))
        // Whoever recorded it may use their own.
        assertTrue(Portrait.mayTakeFromArchive(private, theo, listOf(person())))
    }

    @Test
    fun `a consent block on the narrator blocks their photograph too`() {
        val undecided = person(consentGranted = false)
        val told = story(narrators = listOf("p_ruth"), createdBy = "u_theo")

        assertFalse(Portrait.mayTakeFromArchive(told, dana, listOf(undecided)))
    }

    @Test
    fun `another family's photograph is never available`() {
        val theirs = story().copy(familyId = "fam_2")
        assertFalse(Portrait.mayTakeFromArchive(theirs, dana, listOf(person())))
    }

    @Test
    fun `a deleted record is not available either`() {
        assertFalse(Portrait.mayTakeFromArchive(null, dana, listOf(person())))
    }

    // --- initials ---

    @Test
    fun `initials take two letters however many names somebody has`() {
        assertEquals("RD", Portrait.initialsOf("Ruth Delaney"))
        assertEquals("MO", Portrait.initialsOf("Miss Opal"))
        assertEquals("R", Portrait.initialsOf("Ruth"))
        assertEquals("ME", Portrait.initialsOf("Mary Ellen van der Berg Delaney"))
        assertEquals("", Portrait.initialsOf(""))
    }
}
