package com.arv.app.core.data

import com.arv.app.core.data.local.MemberEntity
import com.arv.app.core.data.local.PersonEntity
import com.arv.app.core.model.MemberRole

/**
 * Who stands in a family, decided without a database.
 *
 * Pure so the screen, the service and the tests all ask the same question.
 */
object Membership {

    /**
     * What to make of an archive that predates member rows.
     *
     * The rule is deliberately narrow: before the members table, the only way into a family
     * was to create it, and creating it linked the owner's account to their own person row.
     * So an account with a linked person and no member row is the owner, and the row written
     * from here says only what was already true. Anyone else gets nothing. A missing row is
     * never turned into a role by guessing.
     */
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

    /**
     * Whether [actor] may take [target] out of the family.
     *
     * The same answer firestore.rules gives, which is that the owner removes and nobody
     * removes themselves, plus one thing the server cannot see: the owner is never
     * removable. A family whose owner row is gone has nobody left who can let anyone in or
     * take anyone out, and no screen should be one tap away from that.
     */
    fun canRemove(actor: MemberEntity?, target: MemberEntity): Boolean =
        actor != null &&
            actor.familyId == target.familyId &&
            actor.role == MemberRole.OWNER &&
            target.userId != actor.userId &&
            target.role != MemberRole.OWNER
}
