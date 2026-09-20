package com.arv.app.core.data

import com.arv.app.core.data.local.InviteEntity
import com.arv.app.core.data.local.MemberEntity
import com.arv.app.core.model.MemberRole
import com.arv.app.core.remote.InviteRemote
import com.arv.app.core.remote.RemoteLookup
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
    suspend fun previewInvite(typed: String?): InviteEntity?
    /** Writes down what the server says became of a code this phone issued. */
    suspend fun recordInviteFate(theirs: InviteEntity)
    suspend fun memberRowFor(familyId: String, userId: String): MemberEntity?
    suspend fun admitMember(member: MemberEntity)
    suspend fun removeMember(familyId: String, userId: String)
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

    /**
     * [replaced] is the code this phone was still showing as live when the server said it was
     * finished: spent on somebody else's phone, or withdrawn. Set so the screen can say why
     * the code changed, rather than quietly showing a different one.
     */
    data class Minted(val invite: InviteEntity, val reach: Reach, val replaced: InviteEntity? = null)

    /** What taking somebody out of the family came to. */
    sealed interface Removal {
        /** Gone from this phone, and from the server too when [everywhere]. */
        data class Removed(val everywhere: Boolean) : Removal
        /** Only the owner removes, never themselves, and the owner is never removed. Nothing was tried. */
        data object NotAllowed : Removal
        /** Not in this family on this phone, so there is nobody here to take out. */
        data object NotAMember : Removal
        /** The server could not be reached. Nothing was removed anywhere. */
        data object CouldNotReach : Removal
    }

    /** What redeeming a code came to. */
    sealed interface Joined {
        data class In(val member: MemberEntity, val familyName: String?) : Joined
        data class Refused(val why: Invitation.Result) : Joined
    }

    suspend fun ensureCode(familyId: String, userId: String, familyName: String?, nowMillis: Long): Minted {
        val invite = local.inviteCodeFor(familyId, userId, familyName, nowMillis)
        val sent = publish(invite, familyName, userId)
        if (!sent.codeRefusedOrUnreached) return Minted(invite, sent.reach)

        // A refused write and a dead connection look the same from a failed write, and they
        // are not. A code spent on another phone is spent on the server and still live here,
        // because the spend happened there. The rules will not let its issuer write over a
        // spent code, so every visit to this screen was refused and reported as no signal,
        // under a code that no longer worked. Ask what the server holds before saying so.
        val theirs = (remote.lookup(invite.code) as? RemoteLookup.Found)?.invite
        val finished = theirs?.takeIf { it.usedAt != null || it.revokedAt != null }
            ?: return Minted(invite, sent.reach)
        local.recordInviteFate(finished)
        val fresh = local.inviteCodeFor(familyId, userId, familyName, nowMillis)
        // The same code back means the phone could not write the fate down. Say what is
        // known rather than going round again.
        if (fresh.code == invite.code) return Minted(invite, sent.reach)
        return Minted(fresh, publish(fresh, familyName, userId).reach, replaced = finished)
    }

    /**
     * What a code opens, before anyone agrees to it. This phone's answer first, then the
     * server's, because a code read down a phone line was never on the phone typing it in.
     */
    suspend fun preview(typed: String?): InviteEntity? {
        local.previewInvite(typed)?.let { return it }
        val code = InviteCode.normalize(typed) ?: return null
        if (!remote.available) return null
        return (remote.lookup(code) as? RemoteLookup.Found)?.invite
    }

    suspend fun replaceCode(familyId: String, userId: String, familyName: String?, nowMillis: Long): Minted {
        val retiring = local.liveInviteFor(familyId, userId, nowMillis)
        val fresh = local.replaceInviteCode(familyId, userId, familyName, nowMillis)
        // Best effort: the local row is already withdrawn. If this does not reach, the old
        // code keeps working on other phones until it expires, which is the one thing a
        // replaced code cannot fully answer for offline. Two weeks bounds it.
        if (retiring != null && remote.available) remote.revoke(retiring, nowMillis)
        return Minted(fresh, publish(fresh, familyName, userId).reach)
    }

    suspend fun redeem(typed: String?, userId: String, nowMillis: Long): Joined {
        val here = local.redeemInvite(typed, userId, nowMillis)
        if (here is Invitation.Result.Accepted) return Joined.In(here.member, here.spent.familyName)
        // Only a code this phone has never heard of goes to the server. Every other local
        // answer (spent, withdrawn, expired, your own) is final and needs no network.
        if (here !is Invitation.Result.Unknown) return Joined.Refused(here)

        return when (val away = remote.redeem(typed ?: "", userId, nowMillis)) {
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

    /**
     * Takes somebody out of the family.
     *
     * The server goes first and this phone follows only if the server agreed. Removal is for
     * the other phones: somebody taken out here but still standing on the server is still in
     * the family everywhere else, and a screen that said "removed" in that state would be
     * promising the family something it cannot rely on.
     */
    suspend fun removeMember(familyId: String, actorUserId: String, targetUserId: String): Removal {
        val target = local.memberRowFor(familyId, targetUserId) ?: return Removal.NotAMember
        val actor = local.memberRowFor(familyId, actorUserId)
        if (!Membership.canRemove(actor, target)) return Removal.NotAllowed
        val everywhere = when (remote.removeMember(familyId, targetUserId)) {
            RemoteWrite.Failed -> return Removal.CouldNotReach
            RemoteWrite.Done -> true
            RemoteWrite.Skipped -> false
        }
        local.removeMember(familyId, targetUserId)
        return Removal.Removed(everywhere)
    }

    /**
     * [codeRefusedOrUnreached] is true only when the write of the code itself failed. Every
     * earlier way out means the code was never offered to the server, so there is nothing
     * there to ask about.
     */
    private data class Sent(val reach: Reach, val codeRefusedOrUnreached: Boolean = false)

    private suspend fun publish(invite: InviteEntity, familyName: String?, userId: String): Sent {
        if (!remote.available) return Sent(Reach.NoServer)
        val me = local.memberRowFor(invite.familyId, userId) ?: return Sent(Reach.ThisPhoneOnly)
        // The server will not take a code for a family it has never heard of, and only the
        // owner may introduce one. A keeper who joined through a code is already known
        // there, because their own join wrote their row.
        if (me.role == MemberRole.OWNER) {
            val name = familyName ?: invite.familyName ?: ""
            if (remote.registerFamily(invite.familyId, name, me) == RemoteWrite.Failed) return Sent(Reach.ThisPhoneOnly)
        }
        return when (remote.publish(invite)) {
            RemoteWrite.Done -> Sent(Reach.OtherPhones)
            RemoteWrite.Failed -> Sent(Reach.ThisPhoneOnly, codeRefusedOrUnreached = true)
            RemoteWrite.Skipped -> Sent(Reach.NoServer)
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

    override suspend fun previewInvite(typed: String?) = repo.previewInvite(typed)

    override suspend fun recordInviteFate(theirs: InviteEntity) = repo.recordInviteFate(theirs)

    override suspend fun memberRowFor(familyId: String, userId: String) = repo.memberRowFor(familyId, userId)

    override suspend fun admitMember(member: MemberEntity) = repo.admitMember(member)

    override suspend fun removeMember(familyId: String, userId: String) = repo.removeMember(familyId, userId)
}
