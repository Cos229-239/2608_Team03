package com.arv.app.core.sync

import com.arv.app.core.data.local.AssetEntity
import com.arv.app.core.data.local.StoryEntity
import com.arv.app.core.model.ArchiveArea
import com.arv.app.core.model.AssetType
import com.arv.app.core.model.StoryKind
import com.arv.app.core.model.UploadState
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
    fun `a file is waiting when its story is shared and it has not gone up, and not otherwise`() {
        fun file(id: String, storyId: String, state: UploadState = UploadState.LOCAL_ONLY, path: String = "/here/" + id) =
            AssetEntity(
                assetId = id, storyId = storyId, familyId = "fam_1", type = AssetType.AUDIO,
                localPath = path, mimeType = "audio/mp4", uploadState = state
            )
        val stories = listOf(
            story(),
            story(visibility = Visibility.PRIVATE).copy(storyId = "s_private"),
            story(deletedAt = 5L).copy(storyId = "s_deleted")
        )
        val files = listOf(
            file("a_waiting", "s_1"),
            file("a_record_only", "s_1", UploadState.UPLOADING),
            file("a_up", "s_1", UploadState.SYNCED),
            file("a_private", "s_private"),
            file("a_deleted", "s_deleted"),
            file("a_gone", "s_1", path = "/gone"),
            file("a_no_story", "s_nowhere")
        )

        assertEquals(2, SyncPolicy.filesWaiting(files, stories) { it != "/gone" })
    }

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
    fun `a deleted story waits thirty days, then is due to be erased`() {
        val deletedAt = 1_000_000L
        val thirtyDays = 30L * 24 * 60 * 60 * 1000
        assertFalse(SyncPolicy.dueToErase(deletedAt, deletedAt + thirtyDays - 1))
        assertTrue(SyncPolicy.dueToErase(deletedAt, deletedAt + thirtyDays))
        assertFalse("a story nobody deleted is never erased", SyncPolicy.dueToErase(null, deletedAt + thirtyDays))
    }

    @Test
    fun `an edit is stamped later than the version it replaces, even on a slow clock`() {
        assertEquals("a clock behind the last edit still lands after it", 1_001L, SyncPolicy.stamp(1_000L, 900L))
        assertEquals("a clock ahead uses the clock", 5_000L, SyncPolicy.stamp(1_000L, 5_000L))
    }
}
