package com.arv.app.core.data

import com.arv.app.core.data.local.InviteEntity
import com.arv.app.core.data.local.MemberEntity

/**
 * What happens when somebody types a code in.
 *
 * Pure so it can be tested without a database, same as [Membership]. Every refusal is a
 * distinct answer rather than one "invalid code", because the person standing there needs
 * to know whether to retype it, ask for a new one, or stop trying. "Invalid" tells them
 * none of that and sends them back to the person who invited them with nothing useful.
 */
object Invitation {

    sealed interface Result {
        /** Good code. [member] is the row to write, [spent] is the invite to close out. */
        data class Accepted(val member: MemberEntity, val spent: InviteEntity) : Result

        /** Not shaped like a code at all. They are still typing, or they mistyped badly. */
        data object NotACode : Result

        /** Shaped right, but no such invitation. A typo that happens to be well formed. */
        data object Unknown : Result

        /**
         * Already spent. Distinct from [Unknown] on purpose: this one means "ask them for
         * a fresh code", which is a thing the person can actually act on.
         */
        data object AlreadyUsed : Result

        /** The person who issued it took it back before anyone used it. */
        data object Revoked : Result

        /** Their own code. Nobody invites themselves into a family. */
        data object YourOwn : Result

        /** They are already in. Not an error, just nothing to do. */
        data object AlreadyInThisFamily : Result
    }

    fun redeem(
        typed: String?,
        /** The row matching [typed] once normalized, or null if there wasn't one. */
        invite: InviteEntity?,
        /** This account's existing standing in [InviteEntity.familyId], if any. */
        existingMember: MemberEntity?,
        userId: String,
        nowMillis: Long
    ): Result {
        InviteCode.normalize(typed) ?: return Result.NotACode
        if (invite == null) return Result.Unknown
        if (invite.revokedAt != null) return Result.Revoked
        if (invite.usedAt != null) return Result.AlreadyUsed
        if (invite.issuedByUserId == userId) return Result.YourOwn
        if (existingMember != null) return Result.AlreadyInThisFamily

        return Result.Accepted(
            member = MemberEntity(
                familyId = invite.familyId,
                userId = userId,
                role = invite.grantsRole,
                // The whole reason the code is per person. Written once, at the moment it
                // is true, and never inferred afterwards.
                invitedBy = invite.issuedByUserId,
                joinedAt = nowMillis
            ),
            spent = invite.copy(usedAt = nowMillis, usedByUserId = userId)
        )
    }
}
