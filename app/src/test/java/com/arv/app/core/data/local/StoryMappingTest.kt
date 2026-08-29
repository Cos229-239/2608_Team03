package com.arv.app.core.data.local

import com.arv.app.core.ai.MemoryAccess
import com.arv.app.core.ai.Viewer
import com.arv.app.core.model.MemberRole
import com.arv.app.core.model.StoryKind
import com.arv.app.core.model.Visibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mapper is where a permission check quietly dies.
 *
 * canRead compares the story's family to the viewer's first and fails closed. The mapper
 * built the domain Story without its familyId, so every story loaded from the database
 * carried "" and every screen rejected it: the archive rendered empty while 123 tests
 * stayed green, because each of them constructed Story by hand and none went through the
 * mapper. This one goes through the mapper.
 */
class StoryMappingTest {

    private fun entity() = StoryEntity(
        storyId = "s_1",
        familyId = "fam_1",
        title = "The night the levee broke",
        kind = StoryKind.AUDIO,
        visibility = Visibility.FAMILY,
        createdBy = "u_1",
        createdAt = 100L,
        updatedAt = 100L
    )

    @Test
    fun `a mapped story keeps its family`() {
        assertEquals("fam_1", entity().toDomain().familyId)
    }

    @Test
    fun `a mapped story is readable by its own family`() {
        val viewer = Viewer(userId = "u_2", role = MemberRole.CONTRIBUTOR, familyId = "fam_1")
        assertTrue(MemoryAccess.canRead(entity().toDomain(), viewer))
    }

    @Test
    fun `a mapped story is still closed to another family`() {
        val stranger = Viewer(userId = "u_9", role = MemberRole.CONTRIBUTOR, familyId = "fam_2")
        assertTrue(!MemoryAccess.canRead(entity().toDomain(), stranger))
    }
}
