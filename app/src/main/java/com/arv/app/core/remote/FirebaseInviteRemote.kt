package com.arv.app.core.remote

import com.arv.app.core.data.Invitation
import com.arv.app.core.data.local.InviteEntity
import com.arv.app.core.data.local.MemberEntity
import com.arv.app.core.model.MemberRole
import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [InviteRemote] on Firestore and one Cloud Function.
 *
 * Construction throws when the app has no Firebase configuration, which is how a build
 * without google-services.json ends up with [InviteRemote.None] instead. See ServiceLocator.
 *
 * Document shapes are the ones firestore.rules and docs/SPEC.md describe. The issuer is
 * called createdBy there because that is the name every other collection uses for the
 * same idea, and the rules enforce it; on the phone the column is issuedByUserId. One
 * mapping, in this file, in both directions.
 */
class FirebaseInviteRemote(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance(REGION)
) : InviteRemote {

    override val available = true

    override suspend fun registerFamily(familyId: String, familyName: String, owner: MemberEntity): RemoteWrite {
        val family = db.document("families/$familyId")
        val me = family.collection("members").document(owner.userId)
        // Reading your own member row is allowed exactly when it exists, so a read that
        // fails is the signal that the family has not been registered yet. Only the owner
        // may create the family, and only in the same batch as their own row.
        val already = runCatching { me.get().awaitTask().exists() }.getOrDefault(false)
        if (already) return RemoteWrite.Done
        return write {
            db.batch()
                .set(
                    family,
                    mapOf("name" to familyName, "createdBy" to owner.userId, "createdAt" to owner.joinedAt),
                    SetOptions.merge()
                )
                .set(
                    me,
                    mapOf(
                        "role" to owner.role.name,
                        "personId" to owner.personId,
                        "branchRootPersonId" to owner.branchRootPersonId,
                        // Empty until the server holds stories to read. BRANCH fails closed there
                        // exactly as it does on a phone where nobody has been placed in the tree.
                        "ancestorPersonIds" to emptyList<String>(),
                        "joinedAt" to owner.joinedAt,
                        "invitedBy" to owner.invitedBy
                    )
                )
                .commit().awaitTask()
        }
    }

    override suspend fun publish(invite: InviteEntity): RemoteWrite = write {
        db.document("families/${invite.familyId}/invites/${invite.code}")
            .set(invite.toDocument()).awaitTask()
    }

    override suspend fun revoke(invite: InviteEntity, nowMillis: Long): RemoteWrite = write {
        db.document("families/${invite.familyId}/invites/${invite.code}")
            .update("revokedAt", nowMillis).awaitTask()
    }

    override suspend fun redeem(typed: String): RemoteRedeem {
        val data = try {
            functions.getHttpsCallable(REDEEM).call(mapOf("code" to typed)).awaitTask().getData() as? Map<*, *>
        } catch (t: Throwable) {
            return RemoteRedeem.Unreachable
        } ?: return RemoteRedeem.Unreachable

        return when (data["status"]) {
            "ACCEPTED" -> {
                val familyId = data["familyId"] as? String ?: return RemoteRedeem.Unreachable
                // A standing is never guessed. An unreadable role, or OWNER, which no code
                // may grant, is treated as no answer rather than as the weakest role.
                val role = (data["role"] as? String)
                    ?.let { runCatching { MemberRole.valueOf(it) }.getOrNull() }
                    ?.takeIf { it != MemberRole.OWNER }
                    ?: return RemoteRedeem.Unreachable
                RemoteRedeem.Accepted(
                    familyId = familyId,
                    familyName = data["familyName"] as? String,
                    role = role,
                    invitedBy = data["invitedBy"] as? String,
                    joinedAt = (data["joinedAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
                )
            }
            "NOT_A_CODE" -> RemoteRedeem.Refused(Invitation.Result.NotACode)
            "UNKNOWN" -> RemoteRedeem.Refused(Invitation.Result.Unknown)
            "ALREADY_USED" -> RemoteRedeem.Refused(Invitation.Result.AlreadyUsed)
            "REVOKED" -> RemoteRedeem.Refused(Invitation.Result.Revoked)
            "EXPIRED" -> RemoteRedeem.Refused(Invitation.Result.Expired)
            "YOUR_OWN" -> RemoteRedeem.Refused(Invitation.Result.YourOwn)
            "ALREADY_IN_THIS_FAMILY" -> RemoteRedeem.Refused(Invitation.Result.AlreadyInThisFamily)
            else -> RemoteRedeem.Unreachable
        }
    }

    /** Done or Failed, never a throw. The caller has a local copy and a person waiting. */
    private suspend fun write(block: suspend () -> Unit): RemoteWrite =
        try {
            block()
            RemoteWrite.Done
        } catch (t: Throwable) {
            RemoteWrite.Failed
        }

    private fun InviteEntity.toDocument(): Map<String, Any?> = mapOf(
        "code" to code,
        "familyId" to familyId,
        "createdBy" to issuedByUserId,
        "role" to grantsRole.name,
        "createdAt" to createdAt,
        "expiresAt" to expiresAt,
        "usedAt" to usedAt,
        "usedBy" to usedByUserId,
        "revokedAt" to revokedAt,
        "familyName" to familyName
    )

    private companion object {
        const val REGION = "us-central1"
        const val REDEEM = "redeemInvite"
    }
}

private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
