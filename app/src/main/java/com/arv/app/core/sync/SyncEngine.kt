package com.arv.app.core.sync

import com.arv.app.core.data.local.OutboxEntity
import com.arv.app.core.data.local.PersonEntity
import com.arv.app.core.data.local.RelationshipEntity
import com.arv.app.core.data.local.StoryEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The phone half of sync, behind an interface for the same reason [SyncRemote] is. */
interface SyncLocal {
    suspend fun unsyncedStories(familyId: String): List<StoryEntity>
    suspend fun unsyncedPeople(familyId: String): List<PersonEntity>
    suspend fun unsyncedRelationships(familyId: String): List<RelationshipEntity>
    suspend fun pendingRelationshipRemovals(familyId: String): List<OutboxEntity>

    /** Stories erased here that the server still holds. Erasing left no row to compare. */
    suspend fun pendingStoryRemovals(familyId: String): List<OutboxEntity>

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
                        Sent.Done -> { local.storySent(story.storyId, story.updatedAt); sent++ }
                        Sent.Stale -> Unit
                        Sent.Refused -> { local.storyRefused(story.storyId, story.updatedAt); refused++ }
                        Sent.Unreachable -> return Result.Offline
                    }
                SyncPolicy.StoryAction.Withdraw ->
                    when (remote.withdrawStory(familyId, story.storyId)) {
                        // A refusal means it is already gone or was never this account's to
                        // take down. Asking again changes neither, and the story is private
                        // on this phone either way.
                        Sent.Done, Sent.Stale, Sent.Refused -> { local.storySent(story.storyId, null); sent++ }
                        Sent.Unreachable -> return Result.Offline
                    }
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
            is Fetched.Got -> Result.Done(
                sent,
                refused,
                merged = local.merge(familyId) { snapshot -> SyncMerge.plan(snapshot, got, familyId, userId) }
            )
            Fetched.NotAMember -> Result.NotAMember
            Fetched.Refused -> Result.Done(sent, refused, merged = null, pullRefused = true)
            Fetched.Unreachable -> Result.Offline
        }
    }
}
