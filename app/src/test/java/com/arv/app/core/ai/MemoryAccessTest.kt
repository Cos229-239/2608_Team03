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

    private val FAMILY = "fam_1"

    // Dana, Theo and the keeper all descend from Ruth. Marcus is family by marriage and
    // reaches no shared ancestor, which is exactly the case BRANCH exists to separate.
    private val dana = Viewer("u_dana", MemberRole.OWNER, familyId = FAMILY, ancestorIds = setOf("p_dana", "p_ruth"))
    private val theo = Viewer("u_theo", MemberRole.CONTRIBUTOR, familyId = FAMILY, ancestorIds = setOf("p_theo", "p_ruth"))
    private val marcus = Viewer("u_marcus", MemberRole.VIEWER, familyId = FAMILY, ancestorIds = setOf("p_marcus"))
    private val keeper = Viewer("u_keeper", MemberRole.KEEPER, familyId = FAMILY, ancestorIds = setOf("p_kim", "p_ruth"))

    private fun story(
        id: String = "s1",
        visibility: Visibility = Visibility.FAMILY,
        aiUsePolicy: AiUsePolicy = AiUsePolicy.SUMMARY_OK,
        createdBy: String = "u_theo",
        sharedWith: List<String> = emptyList(),
        restricted: Boolean = false,
        area: ArchiveArea = ArchiveArea.STORIES,
        subjects: List<String> = emptyList(),
        branchRootPersonId: String? = null
    ) = Story(
        storyId = id,
        familyId = FAMILY,
        title = "A story",
        kind = StoryKind.AUDIO,
        area = area,
        visibility = visibility,
        branchRootPersonId = branchRootPersonId,
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
    fun `branch is readable by everyone descended from the named ancestor`() {
        val s = story(visibility = Visibility.BRANCH, branchRootPersonId = "p_ruth")
        assertTrue(MemoryAccess.canRead(s, dana))
        assertTrue(MemoryAccess.canRead(s, theo))
        assertTrue(MemoryAccess.canRead(s, keeper))
    }

    @Test
    fun `branch is not readable by relatives outside that line`() {
        val s = story(visibility = Visibility.BRANCH, branchRootPersonId = "p_ruth")
        assertFalse(MemoryAccess.canRead(s, marcus))
    }

    @Test
    fun `branch with no ancestor named fails closed`() {
        // An unanswered "which side of the family" must never resolve to everyone.
        val s = story(visibility = Visibility.BRANCH, branchRootPersonId = null)
        assertFalse(MemoryAccess.canRead(s, dana))
        assertFalse(MemoryAccess.canRead(s, keeper))
        assertFalse(MemoryAccess.canRead(s, marcus))
    }

    @Test
    fun `a branch named after you is yours to read`() {
        // The old shape lost this: someone could file a memory under their own line and
        // then be unable to open it.
        val s = story(visibility = Visibility.BRANCH, branchRootPersonId = "p_dana")
        assertTrue(MemoryAccess.canRead(s, dana))
        assertFalse(MemoryAccess.canRead(s, theo))
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
        val ruth = Viewer("u_ruth", MemberRole.VIEWER, familyId = FAMILY, personIds = setOf("p_ruth"))
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

    @Test
    fun `a story from another family is refused however public it is`() {
        // FAMILY visibility used to return true unconditionally, so the only thing keeping
        // one family out of another was that every query happened to filter first.
        val theirs = story(visibility = Visibility.FAMILY).copy(familyId = "fam_2")
        assertFalse(MemoryAccess.canRead(theirs, dana))
        assertFalse(MemoryAccess.canRead(theirs, keeper))
    }

    @Test
    fun `sharing an ancestor does not reach across families`() {
        // The case that matters once cousins can link their archives.
        val theirs = story(visibility = Visibility.BRANCH, branchRootPersonId = "p_ruth")
            .copy(familyId = "fam_2")
        assertFalse(MemoryAccess.canRead(theirs, dana))
    }

    @Test
    fun `another family's story cannot be edited or deleted either`() {
        // canRead got the family boundary and canEdit did not, which left the more
        // dangerous half open: not reading someone else's archive, but changing it.
        val theirs = story(visibility = Visibility.FAMILY).copy(familyId = "fam_2")
        assertFalse(MemoryAccess.canEdit(theirs, dana))
        assertFalse(MemoryAccess.canEdit(theirs, keeper))
    }

    @Test
    fun `a creator in another family is still refused`() {
        // Ids are not unique across archives, so matching createdBy is not proof of
        // anything on its own.
        val theirs = story(createdBy = "u_dana").copy(familyId = "fam_2")
        assertFalse(MemoryAccess.canEdit(theirs, dana))
    }
}
