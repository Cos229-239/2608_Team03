package com.arv.app.core.remote

import com.arv.app.core.data.InviteCode
import com.arv.app.core.data.Invitation
import com.arv.app.core.data.local.InviteEntity
import com.arv.app.core.data.local.MemberEntity
import com.arv.app.core.model.MemberRole
import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [InviteRemote] on Firestore alone, on the free plan. No server code anywhere.
 *
 * Construction throws when the app has no Firebase configuration, which is how a build
 * without google-services.json ends up with [InviteRemote.None] instead. See ServiceLocator.
 *
 * Redeeming is the phone's own work: read the one code it was given, decide with the same
 * [Invitation.redeem] a local code goes through, then write the member row and spend the
 * code in one batch. firestore.rules accepts that batch only as a pair, checked against
 * the code as it stood before, so a phone that lies about its role, skips the spend, or
 * arrives second is refused by the database rather than by anything it could edit.
 *
 * Document shapes are the ones firestore.rules and docs/SPEC.md describe. The issuer is
 * called createdBy there because that is the name every other collection uses for the
 * same idea; on the phone the column is issuedByUserId. One mapping, in this file, in
 * both directions.
 */
class FirebaseInviteRemote(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()
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
        db.document("invites/${invite.code}").set(invite.toDocument()).awaitTask()
    }

    override suspend fun revoke(invite: InviteEntity, nowMillis: Long): RemoteWrite = write {
        db.document("invites/${invite.code}").update("revokedAt", nowMillis).awaitTask()
    }

    // From the server and never the cache. The phone that issued a code holds its own copy
    // of it, written before anyone spent it, and that copy is the one answer not wanted here.
    override suspend fun lookup(code: String): RemoteLookup =
        try {
            db.document("invites/$code").get(Source.SERVER).awaitTask().toInvite()
                ?.let { RemoteLookup.Found(it) } ?: RemoteLookup.Missing
        } catch (t: Throwable) {
            RemoteLookup.Unreachable
        }

    // Deleting a row that is already gone succeeds, so a second tap is harmless. A refusal
    // from the rules comes back as Failed, and the phone keeps the member rather than
    // pretending the server agreed.
    override suspend fun removeMember(familyId: String, userId: String): RemoteWrite = write {
        db.document("families/$familyId/members/$userId").delete().awaitTask()
    }

    override suspend fun redeem(typed: String, userId: String, nowMillis: Long): RemoteRedeem {
        val code = InviteCode.normalize(typed) ?: return RemoteRedeem.Refused(Invitation.Result.NotACode)
        val inviteRef = db.document("invites/$code")

        val invite = try {
            inviteRef.get().awaitTask().toInvite()
        } catch (t: Throwable) {
            return RemoteRedeem.Unreachable
        }
        // Your own row reads exactly when it exists; a refusal here means no standing yet.
        val existing = invite?.let { inv ->
            runCatching {
                db.document("families/${inv.familyId}/members/$userId").get().awaitTask()
                    .takeIf { it.exists() }?.toMember(inv.familyId, userId)
            }.getOrNull()
        }

        val decision = Invitation.redeem(typed, invite, existing, userId, nowMillis)
        val accepted = decision as? Invitation.Result.Accepted ?: return RemoteRedeem.Refused(decision)
        val member = accepted.member
        val memberRef = db.document("families/${member.familyId}/members/$userId")

        // Both writes or neither. The rules require the row to name the code it spends and
        // the spend to name the row it admits, so neither can be forged on its own.
        try {
            db.batch()
                .set(
                    memberRef,
                    mapOf(
                        "role" to member.role.name,
                        "personId" to null,
                        "branchRootPersonId" to null,
                        "ancestorPersonIds" to emptyList<String>(),
                        "joinedAt" to nowMillis,
                        "invitedBy" to member.invitedBy,
                        "viaCode" to code
                    )
                )
                .update(inviteRef, mapOf("usedAt" to nowMillis, "usedBy" to userId))
                .commit().awaitTask()
        } catch (t: Throwable) {
            // Refused by the rules means the code changed under us: spent or withdrawn by
            // somebody else between the read and the write. Read it again and say which,
            // with the same decision, rather than guessing.
            val again = runCatching { inviteRef.get().awaitTask().toInvite() }.getOrNull()
                ?: return RemoteRedeem.Unreachable
            val why = Invitation.redeem(typed, again, existing, userId, nowMillis)
            return if (why is Invitation.Result.Accepted) RemoteRedeem.Unreachable else RemoteRedeem.Refused(why)
        }

        return RemoteRedeem.Accepted(
            familyId = member.familyId,
            familyName = accepted.spent.familyName,
            role = member.role,
            invitedBy = member.invitedBy,
            joinedAt = nowMillis
        )
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

    /**
     * The document as the phone's own row type, or null when it is missing or unreadable.
     * An unreadable role is treated as no code at all: a standing is never guessed.
     */
    private fun DocumentSnapshot.toInvite(): InviteEntity? {
        if (!exists()) return null
        val role = getString("role")?.let { runCatching { MemberRole.valueOf(it) }.getOrNull() }
            ?.takeIf { it != MemberRole.OWNER } ?: return null
        return InviteEntity(
            code = id,
            familyId = getString("familyId") ?: return null,
            issuedByUserId = getString("createdBy") ?: return null,
            grantsRole = role,
            createdAt = getLong("createdAt") ?: 0L,
            usedAt = getLong("usedAt"),
            usedByUserId = getString("usedBy"),
            revokedAt = getLong("revokedAt"),
            familyName = getString("familyName"),
            expiresAt = getLong("expiresAt")
        )
    }

    private fun DocumentSnapshot.toMember(familyId: String, userId: String) = MemberEntity(
        familyId = familyId,
        userId = userId,
        role = getString("role")?.let { runCatching { MemberRole.valueOf(it) }.getOrNull() } ?: MemberRole.VIEWER,
        personId = getString("personId"),
        branchRootPersonId = getString("branchRootPersonId"),
        joinedAt = getLong("joinedAt") ?: 0L,
        invitedBy = getString("invitedBy")
    )
}

private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
