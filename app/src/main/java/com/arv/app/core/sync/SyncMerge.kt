package com.arv.app.core.sync

import com.arv.app.core.data.local.MemberEntity
import com.arv.app.core.data.local.PersonEntity
import com.arv.app.core.data.local.RelationshipEntity
import com.arv.app.core.data.local.StoryEntity
import com.arv.app.core.model.TranscriptStatus
import com.arv.app.core.model.UploadState

/**
 * What a pull changes on this phone. A pure function from what the phone holds and what the
 * server sent to a list of writes, so every rule below is a unit test rather than a hope.
 *
 * The later edit wins, row by row, compared on updatedAt. Beyond that, two rules carry the
 * weight:
 *
 *  1. Only what came from the server can be taken away by the server. A row is removed for
 *     being absent only if this phone has seen it there (syncedAt is set) and holds no edit to
 *     it still waiting to go. Anything made here and never sent survives any answer the
 *     server gives, including an empty one.
 *
 *  2. Nobody's own stories are removed for being absent. A story leaves the server when its
 *     creator takes it back to private, and the creator's phone is where it went private.
 *     Somebody else's story that stops arriving has been taken back, or stopped being shared
 *     with this account, and this phone lets it go.
 *
 * One exception to the later edit winning: an edit the rules refused. It never reached the
 * family, so the next pull replaces it with the copy the family has, and the phone stops
 * showing something as saved that nobody else will ever see. Settings has already said it
 * was not accepted.
 *
 * Removing a story here removes its rows, never a file. A recording somebody attached to it
 * from this phone is still on this phone's storage.
 */
object SyncMerge {

    data class Local(
        /** Deleted stories included: a delete is an edit like any other. */
        val stories: List<StoryEntity>,
        val people: List<PersonEntity>,
        val relationships: List<RelationshipEntity>,
        val members: List<MemberEntity>,
        /** Links removed on this phone whose removal has not reached the server yet. */
        val removingEdgeIds: Set<String>
    )

    data class Plan(
        val writeStories: List<StoryEntity> = emptyList(),
        val removeStories: List<String> = emptyList(),
        val writePeople: List<PersonEntity> = emptyList(),
        val writeRelationships: List<RelationshipEntity> = emptyList(),
        val removeRelationships: List<RelationshipEntity> = emptyList(),
        val writeMembers: List<MemberEntity> = emptyList(),
        val removeMembers: List<String> = emptyList()
    ) {
        val changesAnything: Boolean
            get() = writeStories.isNotEmpty() || removeStories.isNotEmpty() ||
                writePeople.isNotEmpty() || writeRelationships.isNotEmpty() ||
                removeRelationships.isNotEmpty() || writeMembers.isNotEmpty() ||
                removeMembers.isNotEmpty()
    }

    fun plan(local: Local, got: Fetched.Got, familyId: String, me: String): Plan = Plan(
        writeStories = stories(local.stories, got.stories, familyId),
        removeStories = local.stories
            .filter { mine ->
                mine.storyId !in got.storyIds &&
                    mine.createdBy != me &&
                    mine.syncedAt != null &&
                    !SyncPolicy.hasNews(mine.updatedAt, mine.syncedAt, mine.refusedAt)
            }
            .map { it.storyId },
        writePeople = people(local.people, got.people, familyId),
        writeRelationships = relationships(local, got.relationships, familyId),
        removeRelationships = local.relationships.filter { mine ->
            SyncDocs.edgeId(mine) !in got.relationshipIds &&
                mine.syncedAt != null &&
                !SyncPolicy.hasNews(mine.updatedAt, mine.syncedAt, mine.refusedAt)
        },
        writeMembers = local.members.associateBy { it.userId }.let { mine ->
            got.members.filter { it.familyId == familyId && mine[it.userId] != it }
        },
        removeMembers = local.members.filter { it.userId !in got.memberIds }.map { it.userId }
    )

    /** This row's current version is the one the server turned down. */
    private fun refused(updatedAt: Long, refusedAt: Long?): Boolean = refusedAt == updatedAt

    private fun stories(local: List<StoryEntity>, theirs: List<StoryEntity>, familyId: String): List<StoryEntity> {
        val mine = local.associateBy { it.storyId }
        return theirs.mapNotNull { t ->
            // Nothing this app sends fails either check. A document that does is not one this
            // phone will hold as shared, whoever wrote it.
            if (t.familyId != familyId || !SyncPolicy.shares(t)) return@mapNotNull null
            val m = mine[t.storyId]
            when {
                // New here. Its words and its upload are this phone's to work out, and it has
                // no recording on this phone yet, so there is nothing to transcribe.
                m == null -> t.copy(
                    transcriptStatus = TranscriptStatus.NONE,
                    uploadState = UploadState.LOCAL_ONLY,
                    syncedAt = t.updatedAt,
                    refusedAt = null
                )
                t.updatedAt > m.updatedAt || refused(m.updatedAt, m.refusedAt) -> t.copy(
                    transcriptStatus = m.transcriptStatus,
                    uploadState = m.uploadState,
                    syncedAt = t.updatedAt,
                    refusedAt = null
                )
                // The same version on both sides: only the note that the server has it.
                t.updatedAt == m.updatedAt && m.syncedAt != t.updatedAt -> m.copy(syncedAt = t.updatedAt)
                else -> null
            }
        }
    }

    private fun people(local: List<PersonEntity>, theirs: List<PersonEntity>, familyId: String): List<PersonEntity> {
        val mine = local.associateBy { it.personId }
        return theirs.mapNotNull { t ->
            if (t.familyId != familyId) return@mapNotNull null
            val m = mine[t.personId]
            when {
                m == null -> t.copy(syncedAt = t.updatedAt, refusedAt = null)
                // This phone's face for them and its own words for them stay. See SyncDocs.
                t.updatedAt > m.updatedAt || refused(m.updatedAt, m.refusedAt) -> t.copy(
                    relationLabel = m.relationLabel,
                    portraitPath = m.portraitPath,
                    portraitAssetId = m.portraitAssetId,
                    syncedAt = t.updatedAt,
                    refusedAt = null
                )
                t.updatedAt == m.updatedAt && m.syncedAt != t.updatedAt -> m.copy(syncedAt = t.updatedAt)
                else -> null
            }
        }
    }

    private fun relationships(local: Local, theirs: List<RelationshipEntity>, familyId: String): List<RelationshipEntity> {
        val mine = local.relationships.associateBy { SyncDocs.edgeId(it) }
        return theirs.mapNotNull { t ->
            val id = SyncDocs.edgeId(t)
            // Removed here and still on its way to the server. Pulling it back now would undo
            // the removal before the server has even heard about it.
            if (t.familyId != familyId || id in local.removingEdgeIds) return@mapNotNull null
            val m = mine[id]
            when {
                m == null -> t.copy(syncedAt = t.updatedAt, refusedAt = null)
                t.updatedAt > m.updatedAt || refused(m.updatedAt, m.refusedAt) ->
                    t.copy(syncedAt = t.updatedAt, refusedAt = null)
                t.updatedAt == m.updatedAt && m.syncedAt != t.updatedAt -> m.copy(syncedAt = t.updatedAt)
                else -> null
            }
        }
    }
}
