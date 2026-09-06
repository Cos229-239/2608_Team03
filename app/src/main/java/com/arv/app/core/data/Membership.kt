package com.arv.app.core.data

import com.arv.app.core.data.local.MemberEntity
import com.arv.app.core.data.local.PersonEntity
import com.arv.app.core.model.MemberRole

/**
 * What to make of an archive that predates member rows.
 *
 * Pure so it can be tested without a database. The rule is deliberately narrow: before the
 * members table, the only way into a family was to create it, and creating it linked the
 * owner's account to their own person row. So an account with a linked person and no
 * member row is the owner, and the row written from here says only what was already true.
 * Anyone else gets nothing. A missing row is never turned into a role by guessing.
 */
object Membership {

    fun backfill(
        familyId: String,
        userId: String,
        people: List<PersonEntity>,
        nowMillis: Long
    ): MemberEntity? {
        val me = people.firstOrNull { it.familyId == familyId && it.linkedUserId == userId }
            ?: return null
        return MemberEntity(
            familyId = familyId,
            userId = userId,
            role = MemberRole.OWNER,
            personId = me.personId,
            joinedAt = nowMillis
        )
    }
}
