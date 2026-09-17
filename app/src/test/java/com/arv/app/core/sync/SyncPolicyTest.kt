package com.arv.app.core.sync

import com.arv.app.core.data.local.StoryEntity
import com.arv.app.core.model.ArchiveArea
import com.arv.app.core.model.StoryKind
import com.arv.app.core.model.Visibility
import com.arv.app.core.sync.SyncPolicy.StoryAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What may leave the phone. The decision the family was promised, one case per test. */
class SyncPolicyTest {

    private fun story(
        visibility: Visibility = Visibility.FAMILY,
        area: ArchiveArea = ArchiveArea.STORIES,
        updatedAt: Long = 100L,
        syncedAt: Long? = null,
        refusedAt: Long? = null,
        deletedAt: Long? = null
    ) = StoryEntity(
        storyId = "s_1",
        familyId = "fam_1",
        title = "The night the levee broke",
        kind = StoryKind.AUDIO,
        area = area,
        visibility = visibility,
        createdBy = "u_ruth",
        updatedAt = updatedAt,
        syncedAt = syncedAt,
        refusedAt = refusedAt,
        deletedAt = deletedAt
    )

    @Test
    fun `private and health never leave the phone, whatever else is true`() {
        assertFalse(SyncPolicy.shares(story(visibility = Visibility.PRIVATE)))
        assertFalse(SyncPolicy.shares(story(visibility = Visibility.FAMILY, area = ArchiveArea.HEALTH)))
        assertFalse(SyncPolicy.shares(story(visibility = Visibility.SELECTED, area = ArchiveArea.HEALTH)))
        assertTrue(SyncPolicy.shares(story(visibility = Visibility.FAMILY)))
        assertTrue(SyncPolicy.shares(story(visibility = Visibility.BRANCH, area = ArchiveArea.LINEAGE)))
        assertTrue(SyncPolicy.shares(story(visibility = Visibility.SELECTED, area = ArchiveArea.CULTURE)))
    }

    @Test
    fun `a private story the server never held is nothing to do, however often it is edited`() {
        assertEquals(StoryAction.Nothing, SyncPolicy.decide(story(visibility = Visibility.PRIVATE)))
        assertEquals(
            StoryAction.Nothing,
            SyncPolicy.decide(story(area = ArchiveArea.HEALTH, updatedAt = 900L))
        )
    }

    @Test
    fun `a story taken back to private after it was shared is withdrawn`() {
        assertEquals(
            StoryAction.Withdraw,
            SyncPolicy.decide(story(visibility = Visibility.PRIVATE, updatedAt = 150L, syncedAt = 100L))
        )
        assertEquals(
            StoryAction.Withdraw,
            SyncPolicy.decide(story(area = ArchiveArea.HEALTH, updatedAt = 150L, syncedAt = 100L))
        )
    }

    @Test
    fun `a shared story goes when it has an unsent edit and not once the server has it`() {
        assertEquals(StoryAction.Send, SyncPolicy.decide(story(syncedAt = null)))
        assertEquals(StoryAction.Send, SyncPolicy.decide(story(updatedAt = 150L, syncedAt = 100L)))
        assertEquals(StoryAction.Nothing, SyncPolicy.decide(story(updatedAt = 150L, syncedAt = 150L)))
    }

    @Test
    fun `a refused version is not asked for again until it changes`() {
        assertEquals(StoryAction.Nothing, SyncPolicy.decide(story(updatedAt = 150L, refusedAt = 150L)))
        assertEquals(StoryAction.Send, SyncPolicy.decide(story(updatedAt = 151L, refusedAt = 150L)))
    }

    @Test
    fun `deleted before it was ever shared stays off the server, deleted after goes as a delete`() {
        assertEquals(StoryAction.Nothing, SyncPolicy.decide(story(deletedAt = 150L, updatedAt = 150L)))
        assertEquals(
            StoryAction.Send,
            SyncPolicy.decide(story(deletedAt = 150L, updatedAt = 150L, syncedAt = 100L))
        )
    }

    @Test
    fun `an edit is stamped later than the version it replaces, even on a slow clock`() {
        assertEquals("a clock behind the last edit still lands after it", 1_001L, SyncPolicy.stamp(1_000L, 900L))
        assertEquals("a clock ahead uses the clock", 5_000L, SyncPolicy.stamp(1_000L, 5_000L))
    }
}
