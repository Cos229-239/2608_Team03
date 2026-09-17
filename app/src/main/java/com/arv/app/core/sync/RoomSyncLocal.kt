package com.arv.app.core.sync

import androidx.room.withTransaction
import com.arv.app.core.data.local.ArvDatabase
import com.arv.app.core.data.local.OutboxEntity
import com.arv.app.core.data.local.PersonEntity
import com.arv.app.core.data.local.RelationshipEntity
import com.arv.app.core.data.local.StoryEntity

/** [SyncLocal] on the app's own database. */
class RoomSyncLocal(private val db: ArvDatabase) : SyncLocal {

    override suspend fun unsyncedStories(familyId: String): List<StoryEntity> =
        db.storyDao().unsynced(familyId)

    override suspend fun unsyncedPeople(familyId: String): List<PersonEntity> =
        db.personDao().unsynced(familyId)

    override suspend fun unsyncedRelationships(familyId: String): List<RelationshipEntity> =
        db.relationshipDao().unsynced(familyId)

    override suspend fun pendingRelationshipRemovals(familyId: String): List<OutboxEntity> =
        db.outboxDao().pendingDeletes(SyncPaths.relationships(familyId))

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
        for (userId in p.removeMembers) db.memberDao().remove(familyId, userId)

        p
    }
}
