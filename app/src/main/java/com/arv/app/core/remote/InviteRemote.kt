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
 * codes its keepers have issued, and one call that turns a code into a standing. No
 * recording, story, person or health record goes through here, and there is no method on
 * this interface that could carry one. docs/PRIVACY.md lists exactly these.
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

    /** Asks the server to admit the signed-in account on a code this phone has never seen. */
    suspend fun redeem(typed: String): RemoteRedeem

    /** The remote for a build that has none. Every write is skipped; a redeem cannot reach. */
    object None : InviteRemote {
        override val available = false
        override suspend fun registerFamily(familyId: String, familyName: String, owner: MemberEntity) =
            RemoteWrite.Skipped
        override suspend fun publish(invite: InviteEntity) = RemoteWrite.Skipped
        override suspend fun revoke(invite: InviteEntity, nowMillis: Long) = RemoteWrite.Skipped
        override suspend fun redeem(typed: String): RemoteRedeem = RemoteRedeem.Unreachable
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
