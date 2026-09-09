package com.arv.app.core.data

import com.arv.app.core.ai.Viewer
import com.arv.app.core.data.local.AssetEntity
import com.arv.app.core.model.AssetType
import com.arv.app.core.model.ConsentMethod
import com.arv.app.core.model.MemberRole
import com.arv.app.core.model.Person
import com.arv.app.core.model.Story
import com.arv.app.core.model.StoryKind
import com.arv.app.core.model.Visibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A portrait is a permission decision that looks like a decoration, which is what makes it
 * dangerous. An avatar is drawn on the feed, the people list and a profile header, so a
 * portrait that skipped the filter would leak one photograph onto three screens inside a
 * circle nobody thinks to audit.
 *
 * Every refusal here has to end in initials rather than an error, because failing closed is
 * only cheap if the fallback is a real design.
 */
class PortraitTest {

    private val FAMILY = "fam_1"

    private val dana = Viewer("u_dana", MemberRole.OWNER, familyId = FAMILY)
    private val theo = Viewer("u_theo", MemberRole.CONTRIBUTOR, familyId = FAMILY)
    private val keeper = Viewer("u_keeper", MemberRole.KEEPER, familyId = FAMILY)

    private fun person(
        id: String = "p_ruth",
        name: String = "Ruth Delaney",
        portrait: String? = "a1",
        consentGranted: Boolean = true
    ) = Person(
        personId = id,
        displayName = name,
        consentGranted = consentGranted,
        consentDecidedAt = if (consentGranted) 1L else null,
        consentMethod = if (consentGranted) ConsentMethod.IN_PERSON else null,
        portraitAssetId = portrait
    )

    private fun story(
        id: String = "s1",
        visibility: Visibility = Visibility.FAMILY,
        createdBy: String = "u_theo",
        narrators: List<String> = emptyList()
    ) = Story(
        storyId = id,
        familyId = FAMILY,
        title = "A photograph of Ruth",
        kind = StoryKind.PHOTO_SET,
        visibility = visibility,
        narratorIds = narrators,
        subjectPersonIds = listOf("p_ruth"),
        createdBy = createdBy
    )

    private fun asset(id: String = "a1", storyId: String = "s1", path: String = "/f/ruth.jpg") =
        AssetEntity(
            assetId = id,
            storyId = storyId,
            familyId = FAMILY,
            type = AssetType.IMAGE,
            localPath = path,
            mimeType = "image/jpeg"
        )

    private val onDisk: (String) -> Boolean = { true }
    private val notOnDisk: (String) -> Boolean = { false }

    @Test
    fun `a family photograph is drawn`() {
        val result = Portrait.resolve("a1", asset(), story(), dana, listOf(person()), onDisk)
        assertEquals(Portrait.Result.Show("/f/ruth.jpg"), result)
    }

    @Test
    fun `nobody has chosen a face`() {
        val result = Portrait.resolve(null, null, null, dana, listOf(person(portrait = null)), onDisk)
        assertEquals(Portrait.Result.NotSet, result)
    }

    /**
     * The one that matters. A private photograph must not reach the people list because
     * somebody set it as a face, and the owner of the archive is not an exception.
     */
    @Test
    fun `a private photograph is withheld from everyone but whoever filed it`() {
        val private = story(visibility = Visibility.PRIVATE, createdBy = "u_theo")

        assertEquals(
            Portrait.Result.Withheld,
            Portrait.resolve("a1", asset(), private, dana, listOf(person()), onDisk)
        )
        assertEquals(
            Portrait.Result.Withheld,
            Portrait.resolve("a1", asset(), private, keeper, listOf(person()), onDisk)
        )
        // And the person who recorded it still sees their own.
        assertEquals(
            Portrait.Result.Show("/f/ruth.jpg"),
            Portrait.resolve("a1", asset(), private, theo, listOf(person()), onDisk)
        )
    }

    @Test
    fun `a consent block on the narrator withholds their face too`() {
        // The photograph is a family photograph, but the person speaking in that record has
        // no consent on file, so the record is restricted and the face goes with it.
        val undecided = person(consentGranted = false)
        val told = story(narrators = listOf("p_ruth"), createdBy = "u_theo")

        assertEquals(
            Portrait.Result.Withheld,
            Portrait.resolve("a1", asset(), told, dana, listOf(undecided), onDisk)
        )
    }

    @Test
    fun `another family's photograph is never drawn`() {
        val theirs = story().copy(familyId = "fam_2")
        assertEquals(
            Portrait.Result.Withheld,
            Portrait.resolve("a1", asset(), theirs, dana, listOf(person()), onDisk)
        )
    }

    @Test
    fun `a deleted story or asset is missing rather than withheld`() {
        // Distinguished on purpose: treating a deletion as a permission failure would hide
        // a dangling pointer the app could offer to repair.
        assertEquals(
            Portrait.Result.Missing,
            Portrait.resolve("a1", null, story(), dana, listOf(person()), onDisk)
        )
        assertEquals(
            Portrait.Result.Missing,
            Portrait.resolve("a1", asset(), null, dana, listOf(person()), onDisk)
        )
    }

    @Test
    fun `a file gone from disk is missing`() {
        assertEquals(
            Portrait.Result.Missing,
            Portrait.resolve("a1", asset(), story(), dana, listOf(person()), notOnDisk)
        )
    }

    /**
     * Ordering check. Permission is read before the disk, so repairing a missing file can
     * never be a route to finding out what a private photograph held.
     */
    @Test
    fun `permission is decided before the file is looked for`() {
        val private = story(visibility = Visibility.PRIVATE, createdBy = "u_theo")
        assertEquals(
            Portrait.Result.Withheld,
            Portrait.resolve("a1", asset(), private, dana, listOf(person()), notOnDisk)
        )
    }

    @Test
    fun `initials take two letters however many names somebody has`() {
        assertEquals("RD", Portrait.initialsOf("Ruth Delaney"))
        assertEquals("MO", Portrait.initialsOf("Miss Opal"))
        assertEquals("R", Portrait.initialsOf("Ruth"))
        assertEquals("ME", Portrait.initialsOf("Mary Ellen van der Berg Delaney"))
        assertEquals("", Portrait.initialsOf(""))
    }

    @Test
    fun `every refusal is a state the circle can render`() {
        // The contract the avatar depends on: nothing here throws, and anything that is not
        // Show means initials.
        val refusals = listOf(
            Portrait.resolve(null, null, null, dana, emptyList(), onDisk),
            Portrait.resolve("a1", null, null, dana, emptyList(), onDisk),
            Portrait.resolve("a1", asset(), story(visibility = Visibility.PRIVATE, createdBy = "u_x"), dana, listOf(person()), onDisk),
            Portrait.resolve("a1", asset(), story(), dana, listOf(person()), notOnDisk)
        )
        assertTrue(refusals.none { it is Portrait.Result.Show })
    }
}
