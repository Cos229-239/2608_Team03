package com.arv.app.core.ai

import com.arv.app.core.model.AiUsePolicy
import com.arv.app.core.model.ArchiveArea
import com.arv.app.core.model.LibrarianScope
import com.arv.app.core.model.MemberRole
import com.arv.app.core.model.Story
import com.arv.app.core.model.StoryKind
import com.arv.app.core.model.Visibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The permission filter is the one place in this app where a bug is a privacy incident
 * rather than a defect, so every branch is covered here and the dangerous cases are
 * written as explicit "must not" assertions.
 */
class MemoryAccessTest {

    private val dana = Viewer("u_dana", MemberRole.OWNER, branchRootPersonId = "p_ruth")
    private val theo = Viewer("u_theo", MemberRole.CONTRIBUTOR, branchRootPersonId = "p_ruth")
    private val marcus = Viewer("u_marcus", MemberRole.VIEWER, branchRootPersonId = null)
    private val keeper = Viewer("u_keeper", MemberRole.KEEPER, branchRootPersonId = "p_ruth")

    private fun story(
        id: String = "s1",
        visibility: Visibility = Visibility.FAMILY,
        aiUsePolicy: AiUsePolicy = AiUsePolicy.SUMMARY_OK,
        createdBy: String = "u_theo",
        sharedWith: List<String> = emptyList(),
        restricted: Boolean = false,
        area: ArchiveArea = ArchiveArea.STORIES,
        subjects: List<String> = emptyList()
    ) = Story(
        storyId = id,
        title = "A story",
        kind = StoryKind.AUDIO,
        area = area,
        visibility = visibility,
        aiUsePolicy = aiUsePolicy,
        sharedWithUserIds = sharedWith,
        subjectPersonIds = subjects,
        restricted = restricted,
        createdBy = createdBy
    )

    // --- canRead ---

    @Test
    fun `family visibility is readable by any member`() {
        val s = story(visibility = Visibility.FAMILY)
        assertTrue(MemoryAccess.canRead(s, dana))
        assertTrue(MemoryAccess.canRead(s, marcus))
    }

    @Test
    fun `private is readable only by its creator`() {
        val s = story(visibility = Visibility.PRIVATE, createdBy = "u_theo")
        assertTrue(MemoryAccess.canRead(s, theo))
        assertFalse(MemoryAccess.canRead(s, dana))
        assertFalse(MemoryAccess.canRead(s, marcus))
    }

    /**
     * The single most important assertion in the codebase. Owners and keepers run the
     * family; they do not get to read what a member kept to themselves. If this test ever
     * needs "fixing" to make a feature work, the feature is wrong.
     */
    @Test
    fun `owners and keepers cannot read someone elses private memory`() {
        val s = story(visibility = Visibility.PRIVATE, createdBy = "u_theo")
        assertFalse(MemoryAccess.canRead(s, dana))
        assertFalse(MemoryAccess.canRead(s, keeper))
    }

    @Test
    fun `selected is readable by named people and by the creator`() {
        val s = story(
            visibility = Visibility.SELECTED,
            createdBy = "u_theo",
            sharedWith = listOf("u_dana")
        )
        assertTrue(MemoryAccess.canRead(s, dana))
        assertTrue(MemoryAccess.canRead(s, theo))
        assertFalse(MemoryAccess.canRead(s, marcus))
    }

    @Test
    fun `branch fails closed while branch scoping is unimplemented`() {
        // DAT-7 will store branchRootPersonId on the story. Until then BRANCH must deny
        // everyone rather than accidentally behaving like FAMILY.
        val s = story(visibility = Visibility.BRANCH)
        assertFalse(MemoryAccess.canRead(s, dana))
        assertFalse(MemoryAccess.canRead(s, keeper))
    }

    @Test
    fun `restricted material is keeper only regardless of visibility`() {
        val s = story(visibility = Visibility.FAMILY, restricted = true)
        assertTrue(MemoryAccess.canRead(s, keeper))
        assertFalse(MemoryAccess.canRead(s, theo))
        assertFalse(MemoryAccess.canRead(s, marcus))
    }

    // --- canLibrarianUse ---

    @Test
    fun `ai policy none blocks both scopes even when readable`() {
        val s = story(visibility = Visibility.FAMILY, aiUsePolicy = AiUsePolicy.NONE)
        assertTrue(MemoryAccess.canRead(s, dana))
        assertFalse(MemoryAccess.canLibrarianUse(s, dana, LibrarianScope.FAMILY))
        assertFalse(MemoryAccess.canLibrarianUse(s, dana, LibrarianScope.PERSONAL))
    }

    @Test
    fun `personal scope reads my own private memories`() {
        val s = story(visibility = Visibility.PRIVATE, createdBy = "u_dana")
        assertTrue(MemoryAccess.canLibrarianUse(s, dana, LibrarianScope.PERSONAL))
    }

    @Test
    fun `family scope never reads private memories, not even my own`() {
        val mine = story(visibility = Visibility.PRIVATE, createdBy = "u_dana")
        val theirs = story(visibility = Visibility.PRIVATE, createdBy = "u_theo")
        assertFalse(MemoryAccess.canLibrarianUse(mine, dana, LibrarianScope.FAMILY))
        assertFalse(MemoryAccess.canLibrarianUse(theirs, dana, LibrarianScope.FAMILY))
    }

    @Test
    fun `quote only is usable but may not be paraphrased`() {
        val s = story(aiUsePolicy = AiUsePolicy.QUOTE_ONLY)
        assertTrue(MemoryAccess.canLibrarianUse(s, dana, LibrarianScope.FAMILY))
        assertFalse(MemoryAccess.mayParaphrase(s))
        assertTrue(MemoryAccess.mayParaphrase(story(aiUsePolicy = AiUsePolicy.SUMMARY_OK)))
    }

    // --- family health archive ---

    /**
     * The rule that makes a shared health archive safe to contribute to. A cousin can
     * write down that your mother had a heart condition, but your mother owns that record.
     */
    @Test
    fun `a health record can be edited by its subject, not only its author`() {
        val ruth = Viewer("u_ruth", MemberRole.VIEWER, null, personIds = setOf("p_ruth"))
        val s = story(
            area = ArchiveArea.HEALTH,
            createdBy = "u_theo",
            subjects = listOf("p_ruth")
        )
        assertTrue(MemoryAccess.canEdit(s, ruth))
        assertTrue(MemoryAccess.canEdit(s, theo))
    }

    @Test
    fun `a keeper cannot edit a health record about someone else`() {
        val s = story(
            area = ArchiveArea.HEALTH,
            createdBy = "u_theo",
            subjects = listOf("p_ruth")
        )
        // Running the family does not make someone else's medical history yours.
        assertFalse(MemoryAccess.canEdit(s, keeper))
        assertFalse(MemoryAccess.canEdit(s, dana))
    }

    @Test
    fun `health records are never paraphrasable, whatever their policy says`() {
        val permissive = story(area = ArchiveArea.HEALTH, aiUsePolicy = AiUsePolicy.SUMMARY_OK)
        assertFalse(MemoryAccess.mayParaphrase(permissive))

        // The same policy on a non-health record is fine.
        assertTrue(MemoryAccess.mayParaphrase(story(aiUsePolicy = AiUsePolicy.SUMMARY_OK)))
    }

    @Test
    fun `health records are still readable by the family that was given them`() {
        // Locking down editing must not accidentally hide the archive from the people
        // it exists to inform.
        val s = story(area = ArchiveArea.HEALTH, visibility = Visibility.FAMILY)
        assertTrue(MemoryAccess.canRead(s, dana))
        assertTrue(MemoryAccess.canRead(s, marcus))
    }

    // --- partition ---

    @Test
    fun `partition counts withheld without exposing them`() {
        val candidates = listOf(
            story(id = "ok", visibility = Visibility.FAMILY),
            story(id = "private", visibility = Visibility.PRIVATE, createdBy = "u_theo"),
            story(id = "noai", aiUsePolicy = AiUsePolicy.NONE)
        )

        val result = MemoryAccess.partition(candidates, dana, LibrarianScope.FAMILY)

        assertEquals(1, result.usable.size)
        assertEquals("ok", result.usable.single().storyId)
        assertEquals(2, result.withheldCount)
        // The withheld stories themselves are not returned, so there is no path by which
        // a title or narrator could leak into a "we found more" message.
        assertFalse(result.usable.any { it.storyId == "private" })
    }
}
