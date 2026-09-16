package com.arv.app.core.remote

import com.arv.app.core.data.Invitation
import com.arv.app.core.data.local.InviteEntity
import com.arv.app.core.data.local.MemberEntity
import com.arv.app.core.model.MemberRole

/**
 * The server half of an invitation, behind an interface so the app builds and tests
 * without one.
 *
 * Three things cross the wire and nothing else: that a family exists and who owns it, the
 * codes its keepers have issued, and the standing a joiner writes for themselves when they
 * spend one. No recording, story, person or health record goes through here, and there is
 * no method on this interface that could carry one. docs/PRIVACY.md lists exactly these.
 * Removing a member deletes one of those rows and carries nothing.
 */
interface InviteRemote {

    /** False on a build with no Firebase configuration. Nothing is attempted. */
    val available: Boolean

    /**
     * Makes the family and its owner known to the server, once.
     *
     * The rules will not let a keeper publish a code into a family the server has never
     * heard of, and will not let anyone but the owner create the family. Idempotent:
     * calling it for a family already registered changes nothing.
     */
    suspend fun registerFamily(familyId: String, familyName: String, owner: MemberEntity): RemoteWrite

    /** Puts a code where another phone's redeem can find it. Idempotent. */
    suspend fun publish(invite: InviteEntity): RemoteWrite

    /** Withdraws a code on the server, so it stops working everywhere, not just here. */
    suspend fun revoke(invite: InviteEntity, nowMillis: Long): RemoteWrite

    /**
     * Redeems a code this phone has never seen: reads it, decides with [Invitation.redeem],
     * and writes the standing and the spend as one batch the rules accept only as a pair.
     */
    suspend fun redeem(typed: String, userId: String, nowMillis: Long): RemoteRedeem

    /**
     * Takes a member out of the family on the server, so no other phone treats them as in it.
     *
     * The rules accept this from the owner alone, and never for the owner's own row.
     */
    suspend fun removeMember(familyId: String, userId: String): RemoteWrite

    /** The remote for a build that has none. Every write is skipped; a redeem cannot reach. */
    object None : InviteRemote {
        override val available = false
        override suspend fun registerFamily(familyId: String, familyName: String, owner: MemberEntity) =
            RemoteWrite.Skipped
        override suspend fun publish(invite: InviteEntity) = RemoteWrite.Skipped
        override suspend fun revoke(invite: InviteEntity, nowMillis: Long) = RemoteWrite.Skipped
        override suspend fun redeem(typed: String, userId: String, nowMillis: Long): RemoteRedeem =
            RemoteRedeem.Unreachable
        override suspend fun removeMember(familyId: String, userId: String) = RemoteWrite.Skipped
    }
}

enum class RemoteWrite {
    /** The server has it. */
    Done,
    /** It did not get there: no network, or the server refused. The local copy stands. */
    Failed,
    /** There is no server on this build. */
    Skipped
}

/** What the server said when asked to redeem a code. */
sealed interface RemoteRedeem {
    data class Accepted(
        val familyId: String,
        val familyName: String?,
        val role: MemberRole,
        val invitedBy: String?,
        val joinedAt: Long
    ) : RemoteRedeem

    /** The server refused for one of the reasons the app itself would have. */
    data class Refused(val why: Invitation.Result) : RemoteRedeem

    /** Never got an answer. Nothing changed anywhere. */
    data object Unreachable : RemoteRedeem
}
