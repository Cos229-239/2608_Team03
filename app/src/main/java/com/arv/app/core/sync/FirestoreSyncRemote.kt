package com.arv.app.core.sync

import com.arv.app.core.data.local.PersonEntity
import com.arv.app.core.data.local.RelationshipEntity
import com.arv.app.core.data.local.StoryEntity
import com.arv.app.core.model.MemberRole
import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [SyncRemote] on Firestore, on the free plan. Files are not part of this: nothing here
 * touches Cloud Storage.
 *
 * Construction throws when the app has no Firebase configuration, which is how a build
 * without google-services.json ends up with [SyncRemote.None]. See ServiceLocator.
 *
 * Every read asks the server itself ([Source.SERVER]), never the SDK's cache. A pull decides
 * what has gone from the family by what is missing from the answer, and a cached answer can be
 * missing things that are still there.
 */
class FirestoreSyncRemote(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()
) : SyncRemote {

    override val available = true

    override suspend fun sendStory(story: StoryEntity, neverSent: Boolean): Sent {
        val ref = db.collection(SyncPaths.stories(story.familyId)).document(story.storyId)
        val doc = SyncDocs.story(story)
        return if (neverSent) attempt { ref.set(doc).awaitTask(); Sent.Done }
        else laterEditWins(ref, story.updatedAt, doc)
    }

    override suspend fun withdrawStory(familyId: String, storyId: String): Sent = attempt {
        db.collection(SyncPaths.stories(familyId)).document(storyId).delete().awaitTask()
        Sent.Done
    }

    override suspend fun sendPerson(person: PersonEntity): Sent =
        laterEditWins(
            db.collection(SyncPaths.people(person.familyId)).document(person.personId),
            person.updatedAt,
            SyncDocs.person(person)
        )

    override suspend fun sendRelationship(edge: RelationshipEntity): Sent =
        laterEditWins(
            db.collection(SyncPaths.relationships(edge.familyId)).document(SyncDocs.edgeId(edge)),
            edge.updatedAt,
            SyncDocs.relationship(edge)
        )

    override suspend fun removeRelationship(familyId: String, edgeId: String): Sent = attempt {
        db.collection(SyncPaths.relationships(familyId)).document(edgeId).delete().awaitTask()
        Sent.Done
    }

    /**
     * Reads the server's copy and writes this one only if the server's is not later, in one
     * transaction, so another phone's write cannot land between the look and the write.
     */
    private suspend fun laterEditWins(ref: DocumentReference, updatedAt: Long, doc: Map<String, Any?>): Sent =
        attempt {
            val wrote = db.runTransaction { tx ->
                val theirs = tx.get(ref).getLong("updatedAt")
                if (theirs != null && theirs > updatedAt) {
                    false
                } else {
                    tx.set(ref, doc)
                    true
                }
            }.awaitTask()
            if (wrote) Sent.Done else Sent.Stale
        }

    override suspend fun fetch(familyId: String, userId: String): Fetched =
        withTimeoutOrNull(FETCH_TIMEOUT_MS) { fetchNow(familyId, userId) } ?: Fetched.Unreachable

    private suspend fun fetchNow(familyId: String, userId: String): Fetched {
        // The member list first. It is readable exactly when this account is in the family,
        // and this account's own row says what else it may ask for.
        val members = try {
            db.collection(SyncPaths.members(familyId)).get(Source.SERVER).awaitTask()
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            return if (t.isRefusal()) Fetched.NotAMember else Fetched.Unreachable
        }
        val mine = members.documents.firstOrNull { it.id == userId } ?: return Fetched.NotAMember
        val role = mine.getString("role")?.let { r -> MemberRole.entries.firstOrNull { it.name == r } }
            ?: return Fetched.Refused
        val ancestors = (mine.get("ancestorPersonIds") as? List<*>)?.filterIsInstance<String>().orEmpty()

        return try {
            val people = db.collection(SyncPaths.people(familyId)).get(Source.SERVER).awaitTask()
            val edges = db.collection(SyncPaths.relationships(familyId)).get(Source.SERVER).awaitTask()
            val stories = storyQueries(familyId, userId, role, ancestors)
                .flatMap { it.get(Source.SERVER).awaitTask().documents }
                .distinctBy { it.id }

            Fetched.Got(
                members = members.documents.mapNotNull { SyncDocs.memberFrom(familyId, it.id, it.fields()) },
                memberIds = members.documents.map { it.id }.toSet(),
                people = people.documents.mapNotNull { SyncDocs.personFrom(it.id, it.fields()) },
                relationships = edges.documents.mapNotNull { SyncDocs.relationshipFrom(it.fields()) },
                relationshipIds = edges.documents.map { it.id }.toSet(),
                stories = stories.mapNotNull { SyncDocs.storyFrom(it.id, it.fields()) },
                storyIds = stories.map { it.id }.toSet()
            )
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            if (t.isRefusal()) Fetched.Refused else Fetched.Unreachable
        }
    }

    /**
     * Every story this account may read, asked for in the shapes firestore.rules can prove.
     * The rules are not filters: a query that could return one document the reader may not
     * see is refused whole, so each of these names the fields its rule reads.
     *
     * Private stories are never on the server, so there is no query for them. A branch story
     * is asked for by the ancestors on this account's member row as the server holds it, not
     * as this phone works them out, because the rule reads the server's row.
     */
    private fun storyQueries(familyId: String, userId: String, role: MemberRole, ancestors: List<String>): List<Query> {
        val keeper = role == MemberRole.OWNER || role == MemberRole.KEEPER
        val stories = db.collection(SyncPaths.stories(familyId))
        return (if (keeper) listOf(false, true) else listOf(false)).flatMap { restricted ->
            val base = stories.whereEqualTo("familyId", familyId).whereEqualTo("restricted", restricted)
            listOf(
                base.whereEqualTo("visibility", "FAMILY"),
                base.whereEqualTo("visibility", "SELECTED").whereArrayContains("sharedWithUserIds", userId),
                base.whereEqualTo("visibility", "SELECTED").whereEqualTo("createdBy", userId)
            ) + ancestors.distinct().chunked(IN_LIMIT).map { chunk ->
                base.whereEqualTo("visibility", "BRANCH").whereIn("branchRootPersonId", chunk)
            }
        }
    }

    /** Sent or refused or unreachable, never a throw. The caller keeps its row either way. */
    private suspend fun attempt(block: suspend () -> Sent): Sent =
        try {
            withTimeoutOrNull(WRITE_TIMEOUT_MS) { block() } ?: Sent.Unreachable
        } catch (c: CancellationException) {
            // The work was stopped, not refused. Let it stop.
            throw c
        } catch (t: Throwable) {
            if (t.isRefusal()) Sent.Refused else Sent.Unreachable
        }

    /**
     * The server said no, as opposed to not answering. A document the rules turn down, or one
     * the server will never accept in this shape, fails the same way every time it is asked.
     */
    private fun Throwable.isRefusal(): Boolean =
        this is FirebaseFirestoreException && (
            code == FirebaseFirestoreException.Code.PERMISSION_DENIED ||
                code == FirebaseFirestoreException.Code.INVALID_ARGUMENT
            )

    private fun DocumentSnapshot.fields(): Map<String, Any?> = data.orEmpty()

    private companion object {
        /** Kept at the smallest limit any Firestore SDK has had for `in`. */
        const val IN_LIMIT = 10

        /**
         * A plain write offline does not fail, it waits for a connection, and a sync that waits
         * forever never gets to say it is offline. The write stays in Firestore's own queue
         * and lands later, which is harmless: the next run sends the same version again.
         */
        const val WRITE_TIMEOUT_MS = 30_000L

        /** A whole pull, every query in it. Past this, call it offline and try later. */
        const val FETCH_TIMEOUT_MS = 90_000L
    }
}

private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
