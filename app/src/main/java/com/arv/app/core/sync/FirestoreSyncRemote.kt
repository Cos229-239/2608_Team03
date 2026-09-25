package com.arv.app.core.sync

import android.net.Uri
import com.arv.app.core.data.local.AssetEntity
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
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.StorageReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import java.io.File
import kotlin.coroutines.resumeWithException

/**
 * [SyncRemote] on Firestore for the records and Cloud Storage for the files.
 *
 * Cloud Storage needs the paid Firebase plan. On a project without it, every upload and
 * download comes back [Sent.Unreachable], which is the same answer as no connection: the
 * records still travel, the bytes wait, and nothing is lost. A removal comes back done,
 * because there is nothing there to take down. So the class works on either plan.
 *
 * Construction throws when the app has no Firebase configuration, which is how a build
 * without google-services.json ends up with [SyncRemote.None]. See ServiceLocator.
 *
 * Every read asks the server itself ([Source.SERVER]), never the SDK's cache. A pull decides
 * what has gone from the family by what is missing from the answer, and a cached answer can be
 * missing things that are still there.
 */
class FirestoreSyncRemote(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val files: StorageReference? = runCatching { FirebaseStorage.getInstance().reference }.getOrNull()
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

    // ---- files
    //
    // The record first, then the bytes, because storage.rules reads the record to decide
    // whether this account may write the file.

    override suspend fun sendAsset(asset: AssetEntity, story: StoryEntity): Sent = attempt {
        db.collection(SyncPaths.assets(asset.familyId)).document(asset.assetId)
            .set(SyncDocs.asset(asset, story)).awaitTask()
        Sent.Done
    }

    override suspend fun uploadAssetFile(remotePath: String, file: File): Sent {
        val ref = files ?: return Sent.Unreachable
        if (!file.isFile) return Sent.Refused
        return attempt(FILE_TIMEOUT_MS) {
            ref.child(remotePath).putFile(Uri.fromFile(file)).awaitTask()
            Sent.Done
        }
    }

    override suspend fun downloadAssetFile(remotePath: String, into: File): Sent {
        val ref = files ?: return Sent.Unreachable
        into.parentFile?.mkdirs()
        val outcome = attempt(FILE_TIMEOUT_MS) {
            ref.child(remotePath).getFile(into).awaitTask()
            Sent.Done
        }
        // A part-written file is worse than none: it would look like the recording and play
        // as nothing. Anything short of Done leaves the phone as it was.
        if (outcome != Sent.Done) into.delete()
        return outcome
    }

    override suspend fun removeAssetFile(remotePath: String): Sent {
        // No Storage in this app means no bytes this phone could have put there, and a removal
        // that can never be tried must not hold up the ones behind it. Taking the record down
        // is what takes the file away from everyone, because storage.rules reads the record.
        val ref = files ?: return Sent.Done
        return try {
            withTimeoutOrNull(WRITE_TIMEOUT_MS) { ref.child(remotePath).delete().awaitTask(); Sent.Done }
                ?: Sent.Unreachable
        } catch (c: CancellationException) {
            throw c
        } catch (e: StorageException) {
            when (e.errorCode) {
                // Nothing there to take down: never uploaded, already removed, or a project
                // with no Storage at all.
                StorageException.ERROR_OBJECT_NOT_FOUND,
                StorageException.ERROR_BUCKET_NOT_FOUND,
                StorageException.ERROR_PROJECT_NOT_FOUND -> Sent.Done
                StorageException.ERROR_NOT_AUTHORIZED -> Sent.Refused
                else -> Sent.Unreachable
            }
        } catch (t: Throwable) {
            Sent.Unreachable
        }
    }

    override suspend fun withdrawAsset(familyId: String, assetId: String): Sent = attempt {
        db.collection(SyncPaths.assets(familyId)).document(assetId).delete().awaitTask()
        Sent.Done
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
            val stories = permissionQueries(SyncPaths.stories(familyId), familyId, userId, role, ancestors)
                .flatMap { it.get(Source.SERVER).awaitTask().documents }
                .distinctBy { it.id }
            // Asset records carry the same permission fields, so they are asked for in the
            // same shapes. One set of queries, so the two can never disagree about who may
            // see what.
            val assets = permissionQueries(SyncPaths.assets(familyId), familyId, userId, role, ancestors)
                .flatMap { it.get(Source.SERVER).awaitTask().documents }
                .distinctBy { it.id }

            Fetched.Got(
                members = members.documents.mapNotNull { SyncDocs.memberFrom(familyId, it.id, it.fields()) },
                memberIds = members.documents.map { it.id }.toSet(),
                people = people.documents.mapNotNull { SyncDocs.personFrom(it.id, it.fields()) },
                relationships = edges.documents.mapNotNull { SyncDocs.relationshipFrom(it.fields()) },
                relationshipIds = edges.documents.map { it.id }.toSet(),
                stories = stories.mapNotNull { SyncDocs.storyFrom(it.id, it.fields()) },
                storyIds = stories.map { it.id }.toSet(),
                assets = assets.mapNotNull { SyncDocs.assetFrom(it.id, it.fields()) },
                assetIds = assets.map { it.id }.toSet()
            )
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            if (t.isRefusal()) Fetched.Refused else Fetched.Unreachable
        }
    }

    /**
     * Everything in [path] this account may read, asked for in the shapes firestore.rules can
     * prove. The rules are not filters: a query that could return one document the reader may
     * not see is refused whole, so each of these names the fields its rule reads.
     *
     * Stories and asset records both carry those fields and are both asked for here, so the
     * two cannot drift into different ideas of who may see what.
     *
     * Private material is never on the server, so there is no query for it. A branch story is
     * asked for by the ancestors on this account's member row as the server holds it, not as
     * this phone works them out, because the rule reads the server's row.
     */
    private fun permissionQueries(path: String, familyId: String, userId: String, role: MemberRole, ancestors: List<String>): List<Query> {
        val keeper = role == MemberRole.OWNER || role == MemberRole.KEEPER
        val collection = db.collection(path)
        return (if (keeper) listOf(false, true) else listOf(false)).flatMap { restricted ->
            val base = collection.whereEqualTo("familyId", familyId).whereEqualTo("restricted", restricted)
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
    private suspend fun attempt(timeoutMs: Long = WRITE_TIMEOUT_MS, block: suspend () -> Sent): Sent =
        try {
            withTimeoutOrNull(timeoutMs) { block() } ?: Sent.Unreachable
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

        /**
         * One file. An hour of audio over a slow connection is minutes, not seconds, and a
         * recording that almost finished uploading is worth waiting for.
         */
        const val FILE_TIMEOUT_MS = 10 * 60_000L
    }
}

private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
