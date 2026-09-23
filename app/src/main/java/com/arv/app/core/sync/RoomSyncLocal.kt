package com.arv.app.core.sync

import androidx.room.withTransaction
import com.arv.app.core.data.local.ArvDatabase
import com.arv.app.core.data.local.AssetEntity
import com.arv.app.core.data.local.OutboxEntity
import com.arv.app.core.data.local.PersonEntity
import com.arv.app.core.data.local.RelationshipEntity
import com.arv.app.core.data.local.StoryEntity
import com.arv.app.core.model.UploadState
import java.io.File

/**
 * [SyncLocal] on the app's own database.
 *
 * [fileDir] is where a downloaded recording or photograph lands. It is the same folder the
 * recorder writes to, so a file that arrived from another phone and one made here are kept
 * in one place and the rest of the app cannot tell them apart.
 */
class RoomSyncLocal(private val db: ArvDatabase, private val fileDir: File? = null) : SyncLocal {

    override suspend fun unsyncedStories(familyId: String): List<StoryEntity> =
        db.storyDao().unsynced(familyId)

    override suspend fun unsyncedPeople(familyId: String): List<PersonEntity> =
        db.personDao().unsynced(familyId)

    override suspend fun unsyncedRelationships(familyId: String): List<RelationshipEntity> =
        db.relationshipDao().unsynced(familyId)

    override suspend fun pendingRelationshipRemovals(familyId: String): List<OutboxEntity> =
        db.outboxDao().pendingDeletes(SyncPaths.relationships(familyId))

    override suspend fun pendingStoryRemovals(familyId: String): List<OutboxEntity> =
        db.outboxDao().pendingDeletes(SyncPaths.stories(familyId))

    // ---- files

    override suspend fun unsentAssets(familyId: String): List<AssetWork> {
        val stories = db.storyDao().allIncludingDeleted(familyId).associateBy { it.storyId }
        return db.assetDao().forFamily(familyId)
            .filter { it.uploadState != UploadState.SYNCED && it.localPath.isNotBlank() }
            .mapNotNull { asset -> stories[asset.storyId]?.let { AssetWork(asset, it) } }
    }

    override suspend fun assetRecorded(assetId: String, remotePath: String) =
        db.assetDao().markUploadState(assetId, UploadState.UPLOADING, remotePath)

    override suspend fun assetUploaded(assetId: String) {
        val asset = db.assetDao().byId(assetId) ?: return
        db.withTransaction {
            db.assetDao().markUploadState(assetId, UploadState.SYNCED, asset.remotePath)
            // The queue row this file has been carrying since before sync existed. The work
            // is done, so the row goes with it.
            db.outboxDao().deleteForDoc(assetId)
        }
    }

    override suspend fun assetRefused(assetId: String) {
        val asset = db.assetDao().byId(assetId) ?: return
        db.assetDao().markUploadState(assetId, UploadState.FAILED, asset.remotePath)
    }

    override suspend fun assetsToFetch(familyId: String): List<AssetEntity> =
        db.assetDao().forFamily(familyId).filter { asset ->
            asset.remotePath != null && (asset.localPath.isBlank() || !File(asset.localPath).isFile)
        }

    override fun fileFor(asset: AssetEntity): File {
        val dir = fileDir ?: File(System.getProperty("java.io.tmpdir") ?: ".", "recordings")
        return File(dir, asset.assetId + "-" + SyncPaths.fileNameFor(asset.remotePath ?: "file"))
    }

    override suspend fun assetArrived(assetId: String, localPath: String) {
        val asset = db.assetDao().byId(assetId) ?: return
        db.assetDao().upsert(asset.copy(localPath = localPath, uploadState = UploadState.SYNCED))
    }

    override suspend fun storySent(storyId: String, updatedAt: Long?) =
        db.storyDao().markSynced(storyId, updatedAt)

    override suspend fun storyRefused(storyId: String, updatedAt: Long) =
        db.storyDao().markRefused(storyId, updatedAt)

    override suspend fun personSent(personId: String, updatedAt: Long) =
        db.personDao().markSynced(personId, updatedAt)

    override suspend fun personRefused(personId: String, updatedAt: Long) =
        db.personDao().markRefused(personId, updatedAt)

    override suspend fun relationshipSent(edge: RelationshipEntity) =
        db.relationshipDao().markSynced(edge.fromPersonId, edge.toPersonId, edge.kind, edge.updatedAt)

    override suspend fun relationshipRefused(edge: RelationshipEntity) =
        db.relationshipDao().markRefused(edge.fromPersonId, edge.toPersonId, edge.kind, edge.updatedAt)

    override suspend fun removalFinished(outboxId: Long) = db.outboxDao().complete(outboxId)

    override suspend fun removalFailed(outboxId: Long, why: String) =
        db.outboxDao().recordFailure(outboxId, why)

    override suspend fun merge(
        familyId: String,
        plan: (SyncMerge.Local) -> SyncMerge.Plan
    ): SyncMerge.Plan = db.withTransaction {
        val p = plan(
            SyncMerge.Local(
                stories = db.storyDao().allIncludingDeleted(familyId),
                people = db.personDao().all(familyId),
                relationships = db.relationshipDao().observeAllOnce(familyId),
                members = db.memberDao().all(familyId),
                assets = db.assetDao().forFamily(familyId),
                removingEdgeIds = db.outboxDao()
                    .pendingDeletes(SyncPaths.relationships(familyId))
                    .map { it.docId }
                    .toSet()
            )
        )

        if (p.writeStories.isNotEmpty()) db.storyDao().upsertAll(p.writeStories)
        for (storyId in p.removeStories) {
            // Rows only. See SyncMerge on why a file is never deleted by a pull.
            for (asset in db.assetDao().forStory(storyId)) {
                db.transcriptDao().clearForAsset(asset.assetId)
                db.outboxDao().deleteForDoc(asset.assetId)
            }
            db.assetDao().deleteForStory(storyId)
            db.outboxDao().deleteForDoc(storyId)
            db.storyDao().deleteById(storyId)
        }

        if (p.writePeople.isNotEmpty()) db.personDao().upsertAll(p.writePeople)

        if (p.writeRelationships.isNotEmpty()) db.relationshipDao().upsertAll(p.writeRelationships)
        for (edge in p.removeRelationships) db.relationshipDao().delete(edge)

        for (member in p.writeMembers) db.memberDao().upsert(member)
        // After the stories, because an asset row whose story is not here yet would be a
        // record of a file nothing in the app can open.
        for (asset in p.writeAssets) db.assetDao().upsert(asset)
        for (userId in p.removeMembers) db.memberDao().remove(familyId, userId)

        p
    }
}
