package com.arv.app.core.sync

import com.arv.app.core.data.local.AssetEntity
import com.arv.app.core.data.local.OutboxEntity
import com.arv.app.core.data.local.PersonEntity
import com.arv.app.core.data.local.RelationshipEntity
import com.arv.app.core.data.local.StoryEntity
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A file this phone holds and the story that decides whether it may travel. They come
 * together because the permission answer lives on the story and nowhere else.
 */
data class AssetWork(val asset: AssetEntity, val story: StoryEntity)

/** A file to take off the server whose row this phone has already erased. */
data class AssetRemoval(val outboxId: Long, val assetId: String, val remotePath: String?)

/** The phone half of sync, behind an interface for the same reason [SyncRemote] is. */
interface SyncLocal {
    suspend fun unsyncedStories(familyId: String): List<StoryEntity>
    suspend fun unsyncedPeople(familyId: String): List<PersonEntity>
    suspend fun unsyncedRelationships(familyId: String): List<RelationshipEntity>
    suspend fun pendingRelationshipRemovals(familyId: String): List<OutboxEntity>

    /** Stories erased here that the server still holds. Erasing left no row to compare. */
    suspend fun pendingStoryRemovals(familyId: String): List<OutboxEntity>

    /** Files on this phone the server has not confirmed, each with its story. */
    suspend fun unsentAssets(familyId: String): List<AssetWork>

    /** The record landed. The bytes have not yet, so the row is not SYNCED. */
    suspend fun assetRecorded(assetId: String, remotePath: String)

    /** The bytes landed too. */
    suspend fun assetUploaded(assetId: String)

    /** The rules refused it, or the file is not on this phone any more. */
    suspend fun assetRefused(assetId: String)

    /** Records this phone has with no file behind them yet. */
    suspend fun assetsToFetch(familyId: String): List<AssetEntity>

    /** Where a downloaded file belongs on this phone. */
    fun fileFor(asset: AssetEntity): File

    suspend fun assetArrived(assetId: String, localPath: String)

    /** This story's files whose records are on the server, as far as this phone knows. */
    suspend fun filesOnServer(storyId: String): List<AssetEntity>

    /** Its file came off the server. The row stays, ready to go up again if the story does. */
    suspend fun assetWithdrawn(assetId: String)

    /** Files of stories erased here that the server still holds. Erasing left no row to ask. */
    suspend fun pendingAssetRemovals(familyId: String): List<AssetRemoval>

    /** Null records that the server no longer holds it. */
    suspend fun storySent(storyId: String, updatedAt: Long?)
    suspend fun storyRefused(storyId: String, updatedAt: Long)
    suspend fun personSent(personId: String, updatedAt: Long)
    suspend fun personRefused(personId: String, updatedAt: Long)
    suspend fun relationshipSent(edge: RelationshipEntity)
    suspend fun relationshipRefused(edge: RelationshipEntity)
    suspend fun removalFinished(outboxId: Long)
    suspend fun removalFailed(outboxId: Long, why: String)

    /**
     * Reads the family, asks [plan] what to change, and writes it, all in one transaction, so
     * an edit made on this phone while a pull is landing is never overwritten by a plan that
     * was made before the edit existed.
     */
    suspend fun merge(familyId: String, plan: (SyncMerge.Local) -> SyncMerge.Plan): SyncMerge.Plan
}

/**
 * Sends what this phone has that the server does not, then, when asked, brings down what the
 * server has that this phone does not.
 *
 * Sending always comes first. A pull decides what has gone from the server by what is missing
 * from its answer, and a removal made here that had not been sent yet would look exactly like
 * something the server never had.
 *
 * Stops at the first sign the server cannot be reached and says so, leaving everything unsent
 * where it was for the next attempt.
 */
class SyncEngine(
    private val local: SyncLocal,
    private val remote: SyncRemote
) {

    sealed interface Result {
        data class Done(
            val sent: Int,
            val refused: Int,
            /** What the pull changed, or null when no pull was asked for or it failed. */
            val merged: SyncMerge.Plan?,
            /** In the family, but part of the pull was refused, so nothing was concluded. */
            val pullRefused: Boolean = false
        ) : Result

        /** Something is still waiting, because the server could not be reached. */
        data object Offline : Result

        /** The server does not count this account as in the family. Nothing here was touched. */
        data object NotAMember : Result

        /** This build has no server. */
        data object NoServer : Result
    }

    /** One run at a time, whoever asks. Two sends of the same row would race each other. */
    private val lock = Mutex()

    val available: Boolean get() = remote.available

    suspend fun run(familyId: String, userId: String, pull: Boolean): Result {
        if (!remote.available) return Result.NoServer
        return lock.withLock { runLocked(familyId, userId, pull) }
    }

    private suspend fun runLocked(familyId: String, userId: String, pull: Boolean): Result {

        var sent = 0
        var refused = 0

        // Files of stories erased here, before the stories: each one bytes first, then record.
        for (removal in local.pendingAssetRemovals(familyId)) {
            when (takeDown(familyId, removal.assetId, removal.remotePath)) {
                // Already gone, or never this account's to take down, counts as done, the same
                // as for a story.
                Sent.Done, Sent.Stale, Sent.Refused -> { local.removalFinished(removal.outboxId); sent++ }
                Sent.Unreachable -> {
                    local.removalFailed(removal.outboxId, "unreachable")
                    return Result.Offline
                }
            }
        }

        for (removal in local.pendingStoryRemovals(familyId)) {
            when (remote.withdrawStory(familyId, removal.docId)) {
                // Already gone, or never this account's to take down. Either way, asking
                // again changes nothing and the story is erased here.
                Sent.Done, Sent.Stale, Sent.Refused -> { local.removalFinished(removal.id); sent++ }
                Sent.Unreachable -> {
                    local.removalFailed(removal.id, "unreachable")
                    return Result.Offline
                }
            }
        }

        for (removal in local.pendingRelationshipRemovals(familyId)) {
            when (remote.removeRelationship(familyId, removal.docId)) {
                Sent.Done, Sent.Stale -> { local.removalFinished(removal.id); sent++ }
                // Only a keeper may remove a link for the whole family. The link stays on the
                // server, and the next pull puts it back here, which is the truth.
                Sent.Refused -> { local.removalFinished(removal.id); refused++ }
                Sent.Unreachable -> {
                    local.removalFailed(removal.id, "unreachable")
                    return Result.Offline
                }
            }
        }

        for (story in local.unsyncedStories(familyId)) {
            when (SyncPolicy.decide(story)) {
                SyncPolicy.StoryAction.Nothing -> Unit
                SyncPolicy.StoryAction.Send ->
                    when (remote.sendStory(story, neverSent = story.syncedAt == null)) {
                        Sent.Done -> {
                            // Each file's record carries a copy of this story's permission
                            // fields, and storage.rules reads that copy. So a story that changes
                            // who may see it changes its files' copies too, and only then counts
                            // as sent: a run that stops halfway sends both again.
                            for (asset in local.filesOnServer(story.storyId)) {
                                when (remote.sendAsset(asset, story)) {
                                    Sent.Done, Sent.Stale -> Unit
                                    Sent.Refused -> refused++
                                    Sent.Unreachable -> return Result.Offline
                                }
                            }
                            local.storySent(story.storyId, story.updatedAt); sent++
                        }
                        Sent.Stale -> Unit
                        Sent.Refused -> { local.storyRefused(story.storyId, story.updatedAt); refused++ }
                        Sent.Unreachable -> return Result.Offline
                    }
                SyncPolicy.StoryAction.Withdraw -> {
                    // Its files first. Private means the recording leaves the server as well,
                    // not only the story that pointed at it.
                    for (asset in local.filesOnServer(story.storyId)) {
                        when (takeDown(familyId, asset.assetId, asset.remotePath)) {
                            Sent.Unreachable -> return Result.Offline
                            Sent.Done, Sent.Stale, Sent.Refused -> local.assetWithdrawn(asset.assetId)
                        }
                    }
                    when (remote.withdrawStory(familyId, story.storyId)) {
                        // A refusal means it is already gone or was never this account's to
                        // take down. Asking again changes neither, and the story is private
                        // on this phone either way.
                        Sent.Done, Sent.Stale, Sent.Refused -> { local.storySent(story.storyId, null); sent++ }
                        Sent.Unreachable -> return Result.Offline
                    }
                }
            }
        }

        // Files, after the stories they belong to. The record goes first and the bytes
        // follow, because storage.rules reads the record to decide who may write the file.
        //
        // Cloud Storage not answering does not make the run offline. Without the paid plan it
        // never answers while Firestore does, and one file that cannot go up must not hold
        // back the family tree or the pull. After the first such file the rest still send
        // their records, and their bytes wait for the next run.
        var storageAnswers = true
        for (work in local.unsentAssets(familyId)) {
            if (!SyncPolicy.sharesFile(work.story)) continue
            val file = File(work.asset.localPath)
            if (!file.isFile) continue
            val remotePath = work.asset.remotePath ?: SyncPaths.assetFile(
                familyId, work.asset.assetId, SyncPaths.fileNameFor(work.asset.localPath)
            )
            if (work.asset.remotePath == null) {
                when (remote.sendAsset(work.asset.copy(remotePath = remotePath), work.story)) {
                    Sent.Done -> local.assetRecorded(work.asset.assetId, remotePath)
                    Sent.Stale -> Unit
                    Sent.Refused -> { local.assetRefused(work.asset.assetId); refused++; continue }
                    Sent.Unreachable -> return Result.Offline
                }
            }
            if (!storageAnswers) continue
            when (remote.uploadAssetFile(remotePath, file)) {
                Sent.Done -> { local.assetUploaded(work.asset.assetId); sent++ }
                Sent.Stale -> Unit
                Sent.Refused -> { local.assetRefused(work.asset.assetId); refused++ }
                // The record is on the server and the bytes are not. The row stays
                // unfinished, so the next run sends only what is missing.
                Sent.Unreachable -> storageAnswers = false
            }
        }

        for (person in local.unsyncedPeople(familyId)) {
            if (!SyncPolicy.hasNews(person.updatedAt, person.syncedAt, person.refusedAt)) continue
            when (remote.sendPerson(person)) {
                Sent.Done -> { local.personSent(person.personId, person.updatedAt); sent++ }
                Sent.Stale -> Unit
                Sent.Refused -> { local.personRefused(person.personId, person.updatedAt); refused++ }
                Sent.Unreachable -> return Result.Offline
            }
        }

        for (edge in local.unsyncedRelationships(familyId)) {
            if (!SyncPolicy.hasNews(edge.updatedAt, edge.syncedAt, edge.refusedAt)) continue
            when (remote.sendRelationship(edge)) {
                Sent.Done -> { local.relationshipSent(edge); sent++ }
                Sent.Stale -> Unit
                Sent.Refused -> { local.relationshipRefused(edge); refused++ }
                Sent.Unreachable -> return Result.Offline
            }
        }

        if (!pull) return Result.Done(sent, refused, merged = null)

        return when (val got = remote.fetch(familyId, userId)) {
            is Fetched.Got -> {
                val merged = local.merge(familyId) { snapshot -> SyncMerge.plan(snapshot, got, familyId, userId) }
                // Files last, and only after their records are written, so a download that
                // cannot finish leaves a phone that still knows what it is missing.
                fetchFiles(familyId)
                Result.Done(sent, refused, merged = merged)
            }
            Fetched.NotAMember -> Result.NotAMember
            Fetched.Refused -> Result.Done(sent, refused, merged = null, pullRefused = true)
            Fetched.Unreachable -> Result.Offline
        }
    }

    /**
     * One file off the server: the bytes, then the record that guards them. The other order
     * would strand the bytes, because storage.rules reads the record to decide who may delete
     * them and nobody could once it was gone. Bytes that cannot be reached yet keep their
     * record, so the next run can finish the job. Bytes the rules refuse to delete lose their
     * record anyway: with it gone nobody can read them either, and bytes nobody can read are
     * better than bytes the old audience still can.
     */
    private suspend fun takeDown(familyId: String, assetId: String, remotePath: String?): Sent {
        if (remotePath != null && remote.removeAssetFile(remotePath) == Sent.Unreachable) return Sent.Unreachable
        return remote.withdrawAsset(familyId, assetId)
    }

    /**
     * Brings down the files behind records this phone now has.
     *
     * A failure here is not an offline result. The records arrived, which is the part that
     * makes the archive readable, and a recording that has not landed yet is a row the next
     * run will try again. Saying the whole pull failed would undo work that succeeded.
     */
    private suspend fun fetchFiles(familyId: String) {
        for (asset in local.assetsToFetch(familyId)) {
            val remotePath = asset.remotePath ?: continue
            val into = local.fileFor(asset)
            if (remote.downloadAssetFile(remotePath, into) == Sent.Done) {
                local.assetArrived(asset.assetId, into.path)
            }
        }
    }
}
