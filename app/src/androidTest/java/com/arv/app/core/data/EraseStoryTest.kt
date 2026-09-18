package com.arv.app.core.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arv.app.core.ai.Viewer
import com.arv.app.core.data.local.ArvDatabase
import com.arv.app.core.data.local.AssetEntity
import com.arv.app.core.data.local.StoryEntity
import com.arv.app.core.data.local.TranscriptSegmentEntity
import com.arv.app.core.model.AssetType
import com.arv.app.core.model.MemberRole
import com.arv.app.core.model.StoryKind
import com.arv.app.core.model.Visibility
import com.arv.app.core.sync.SyncPaths
import com.arv.app.core.sync.SyncPolicy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Deleting hides a story so a mis-tap can be undone. Erasing is the other half of that
 * promise: after thirty days, or when somebody says so, the recording actually goes.
 *
 * An in-memory database and files in the test cache, so nothing here touches the archive on
 * the device running it.
 */
@RunWith(AndroidJUnit4::class)
class EraseStoryTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val db = Room.inMemoryDatabaseBuilder(context, ArvDatabase::class.java).build()
    private val repo = StoryRepository(db)
    private val fam = "fam_1"
    private val me = Viewer(userId = "u_angela", role = MemberRole.OWNER, familyId = fam)

    @After
    fun close() = db.close()

    private fun recording(name: String): File =
        File(context.cacheDir, name).apply { writeBytes(ByteArray(16) { 7 }) }

    private suspend fun story(
        id: String,
        deletedAt: Long?,
        syncedAt: Long? = null,
        createdBy: String = me.userId,
        file: File? = null
    ): StoryEntity {
        val row = StoryEntity(
            storyId = id, familyId = fam, title = "Story $id", kind = StoryKind.AUDIO,
            visibility = Visibility.FAMILY, createdBy = createdBy, createdAt = 10L,
            updatedAt = 10L, deletedAt = deletedAt, syncedAt = syncedAt
        )
        db.storyDao().upsert(row)
        if (file != null) {
            db.assetDao().upsert(
                AssetEntity(
                    assetId = "a_$id", storyId = id, familyId = fam, type = AssetType.AUDIO,
                    localPath = file.path, mimeType = "audio/mp4", createdAt = 10L
                )
            )
            db.transcriptDao().insertAll(
                listOf(TranscriptSegmentEntity(assetId = "a_$id", startMs = 0, endMs = 900, text = "words"))
            )
        }
        return row
    }

    @Test
    fun erasingTakesTheRowsTheTranscriptAndTheRecordingItself() = runBlocking {
        val file = recording("erase-me.m4a")
        story("s_1", deletedAt = 500L, file = file)

        assertTrue(repo.eraseStory("s_1", me, nowMillis = 900L))

        assertNull(db.storyDao().byIdIncludingDeleted("s_1"))
        assertTrue(db.assetDao().forStory("s_1").isEmpty())
        assertTrue(db.transcriptDao().forAssetOnce("a_s_1").isEmpty())
        assertFalse("the recording is gone from storage", file.exists())
    }

    @Test
    fun aStoryTheServerHoldsLeavesAQueuedRemovalBehind() = runBlocking {
        story("s_shared", deletedAt = 500L, syncedAt = 10L)

        repo.eraseStory("s_shared", me, nowMillis = 900L)

        val queued = db.outboxDao().pendingDeletes(SyncPaths.stories(fam))
        assertEquals(listOf("s_shared"), queued.map { it.docId })
    }

    @Test
    fun aStoryTheServerNeverHadQueuesNothing() = runBlocking {
        story("s_local", deletedAt = 500L, syncedAt = null)

        repo.eraseStory("s_local", me, nowMillis = 900L)

        assertTrue(db.outboxDao().pendingDeletes(SyncPaths.stories(fam)).isEmpty())
    }

    @Test
    fun onlyADeletedStoryCanBeErased() = runBlocking {
        story("s_live", deletedAt = null)

        assertFalse(repo.eraseStory("s_live", me, nowMillis = 900L))
        assertTrue(db.storyDao().all(fam).map { it.storyId }.contains("s_live"))
    }

    @Test
    fun somebodyWhoCouldNotEditItCannotEraseIt() = runBlocking {
        story("s_theirs", deletedAt = 500L, createdBy = "u_ruth")
        val viewer = Viewer(userId = "u_dana", role = MemberRole.CONTRIBUTOR, familyId = fam)

        assertFalse(repo.eraseStory("s_theirs", viewer, nowMillis = 900L))
        assertTrue(
            "it is still here, still deleted",
            db.storyDao().byIdIncludingDeleted("s_theirs")?.deletedAt != null
        )
    }

    @Test
    fun thirtyDaysLaterTheWaitingOnesGoAndTheRestStay() = runBlocking {
        val now = 100_000_000L
        story("s_old", deletedAt = now - SyncPolicy.ERASE_AFTER_MS)
        story("s_yesterday", deletedAt = now - (24L * 60 * 60 * 1000))
        story("s_live", deletedAt = null)

        assertEquals(1, repo.purgeExpiredDeleted(fam, me, nowMillis = now))

        assertNull(db.storyDao().byIdIncludingDeleted("s_old"))
        assertEquals(
            listOf("s_yesterday"),
            db.storyDao().observeDeleted(fam).first().map { it.storyId }
        )
        assertEquals(listOf("s_live"), db.storyDao().all(fam).map { it.storyId })
    }
}
