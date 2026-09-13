package com.arv.app.core.data

import com.arv.app.core.data.local.InviteEntity
import com.arv.app.core.data.local.MemberEntity
import com.arv.app.core.model.MemberRole
import com.arv.app.core.remote.InviteRemote
import com.arv.app.core.remote.RemoteRedeem
import com.arv.app.core.remote.RemoteWrite

/**
 * What the phone can do with invitations on its own. [StoryRepository] provides it through
 * [RepositoryInviteLocal]; tests provide a fake.
 */
interface InviteLocal {
    suspend fun inviteCodeFor(familyId: String, userId: String, familyName: String?, nowMillis: Long): InviteEntity
    suspend fun replaceInviteCode(familyId: String, userId: String, familyName: String?, nowMillis: Long): InviteEntity
    suspend fun liveInviteFor(familyId: String, userId: String, nowMillis: Long): InviteEntity?
    suspend fun redeemInvite(typed: String?, userId: String, nowMillis: Long): Invitation.Result
    suspend fun memberRowFor(familyId: String, userId: String): MemberEntity?
    suspend fun admitMember(member: MemberEntity)
}

/**
 * An invitation, from the phone that mints it to the phone that types it in.
 *
 * The local half is authoritative and always runs first: a code on this phone works on
 * this phone with no server at all, which keeps every existing test true and keeps the
 * sample family working on a build with no Firebase configuration. The remote half is
 * what lets a code cross to a phone that has never seen it.
 *
 * Nothing here waits on the network before answering the person locally. A publish that
 * fails leaves a code that works on this phone and says so; a redeem that cannot reach
 * says so and changes nothing.
 */
class InviteService(private val local: InviteLocal, private val remote: InviteRemote) {

    /** How far a code will travel. Shown beside it, because the person reading it out needs to know. */
    enum class Reach {
        /** The server has it. Any phone with the app can redeem it. */
        OtherPhones,
        /** Could not reach the server. Works here; try again online. */
        ThisPhoneOnly,
        /** This build has no server. */
        NoServer
    }

    data class Minted(val invite: InviteEntity, val reach: Reach)

    /** What redeeming a code came to. */
    sealed interface Joined {
        data class In(val member: MemberEntity, val familyName: String?) : Joined
        data class Refused(val why: Invitation.Result) : Joined
    }

    suspend fun ensureCode(familyId: String, userId: String, familyName: String?, nowMillis: Long): Minted {
        val invite = local.inviteCodeFor(familyId, userId, familyName, nowMillis)
        return Minted(invite, publish(invite, familyName, userId))
    }

    suspend fun replaceCode(familyId: String, userId: String, familyName: String?, nowMillis: Long): Minted {
        val retiring = local.liveInviteFor(familyId, userId, nowMillis)
        val fresh = local.replaceInviteCode(familyId, userId, familyName, nowMillis)
        // Best effort: the local row is already withdrawn. If this does not reach, the old
        // code keeps working on other phones until it expires, which is the one thing a
        // replaced code cannot fully answer for offline. Two weeks bounds it.
        if (retiring != null && remote.available) remote.revoke(retiring, nowMillis)
        return Minted(fresh, publish(fresh, familyName, userId))
    }

    suspend fun redeem(typed: String?, userId: String, nowMillis: Long): Joined {
        val here = local.redeemInvite(typed, userId, nowMillis)
        if (here is Invitation.Result.Accepted) return Joined.In(here.member, here.spent.familyName)
        // Only a code this phone has never heard of goes to the server. Every other local
        // answer (spent, withdrawn, expired, your own) is final and needs no network.
        if (here !is Invitation.Result.Unknown) return Joined.Refused(here)

        return when (val away = remote.redeem(typed ?: "")) {
            is RemoteRedeem.Accepted -> {
                val member = MemberEntity(
                    familyId = away.familyId,
                    userId = userId,
                    role = away.role,
                    invitedBy = away.invitedBy,
                    joinedAt = away.joinedAt
                )
                local.admitMember(member)
                Joined.In(member, away.familyName)
            }
            is RemoteRedeem.Refused -> Joined.Refused(away.why)
            RemoteRedeem.Unreachable -> Joined.Refused(Invitation.Result.Unreachable)
        }
    }

    private suspend fun publish(invite: InviteEntity, familyName: String?, userId: String): Reach {
        if (!remote.available) return Reach.NoServer
        val me = local.memberRowFor(invite.familyId, userId) ?: return Reach.ThisPhoneOnly
        // The server will not take a code for a family it has never heard of, and only the
        // owner may introduce one. A keeper who joined through a code is already known
        // there, because the function wrote their row.
        if (me.role == MemberRole.OWNER) {
            val name = familyName ?: invite.familyName ?: ""
            if (remote.registerFamily(invite.familyId, name, me) == RemoteWrite.Failed) return Reach.ThisPhoneOnly
        }
        return when (remote.publish(invite)) {
            RemoteWrite.Done -> Reach.OtherPhones
            RemoteWrite.Failed -> Reach.ThisPhoneOnly
            RemoteWrite.Skipped -> Reach.NoServer
        }
    }
}

/** [InviteLocal] over the repository. One line each; the repository already knows how. */
class RepositoryInviteLocal(private val repo: StoryRepository) : InviteLocal {
    override suspend fun inviteCodeFor(familyId: String, userId: String, familyName: String?, nowMillis: Long) =
        repo.inviteCodeFor(familyId, userId, familyName, nowMillis)

    override suspend fun replaceInviteCode(familyId: String, userId: String, familyName: String?, nowMillis: Long) =
        repo.replaceInviteCode(familyId, userId, familyName, nowMillis)

    override suspend fun liveInviteFor(familyId: String, userId: String, nowMillis: Long) =
        repo.liveInviteFor(familyId, userId, nowMillis)

    override suspend fun redeemInvite(typed: String?, userId: String, nowMillis: Long) =
        repo.redeemInvite(typed, userId, nowMillis)

    override suspend fun memberRowFor(familyId: String, userId: String) = repo.memberRowFor(familyId, userId)

    override suspend fun admitMember(member: MemberEntity) = repo.admitMember(member)
}
