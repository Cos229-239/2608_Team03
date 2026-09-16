package com.arv.app.core.data

import com.arv.app.core.data.local.MemberEntity
import com.arv.app.core.data.local.PersonEntity
import com.arv.app.core.model.MemberRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The backfill is the one place a role is written without somebody choosing it, so it has
 * to be provably unable to invent one.
 */
class MembershipTest {

    private fun person(id: String, family: String = "fam_1", linked: String? = null, steward: String? = null) =
        PersonEntity(
            personId = id,
            familyId = family,
            displayName = id,
            linkedUserId = linked,
            memoryStewardUserId = steward
        )

    @Test
    fun `the account linked to a person in the family is its owner`() {
        val row = Membership.backfill("fam_1", "u_1", listOf(person("p_1", linked = "u_1")), 500L)
        assertEquals(MemberRole.OWNER, row?.role)
        assertEquals("p_1", row?.personId)
        assertEquals("fam_1", row?.familyId)
        assertEquals("u_1", row?.userId)
        assertEquals(500L, row?.joinedAt)
        assertNull("nobody invited the owner", row?.invitedBy)
    }

    @Test
    fun `an account nobody in the family is linked to gets no row`() {
        assertNull(Membership.backfill("fam_1", "u_stranger", listOf(person("p_1", linked = "u_1")), 500L))
    }

    @Test
    fun `an empty family gives nobody a row`() {
        assertNull(Membership.backfill("fam_1", "u_1", emptyList(), 500L))
    }

    @Test
    fun `a person linked in a different family does not count`() {
        assertNull(Membership.backfill("fam_1", "u_1", listOf(person("p_1", family = "fam_2", linked = "u_1")), 500L))
    }

    @Test
    fun `being someone's memory steward is not membership`() {
        assertNull(Membership.backfill("fam_1", "u_1", listOf(person("p_1", steward = "u_1")), 500L))
    }

    // --- who may remove whom ---

    private fun row(user: String, role: MemberRole, family: String = "fam_1") =
        MemberEntity(familyId = family, userId = user, role = role, joinedAt = 1L)

    @Test
    fun `the owner can remove a keeper, a contributor or a viewer`() {
        val owner = row("u_1", MemberRole.OWNER)
        assertTrue(Membership.canRemove(owner, row("u_2", MemberRole.KEEPER)))
        assertTrue(Membership.canRemove(owner, row("u_3", MemberRole.CONTRIBUTOR)))
        assertTrue(Membership.canRemove(owner, row("u_4", MemberRole.VIEWER)))
    }

    @Test
    fun `nobody but the owner removes anybody`() {
        val target = row("u_4", MemberRole.VIEWER)
        assertFalse(Membership.canRemove(row("u_2", MemberRole.KEEPER), target))
        assertFalse(Membership.canRemove(row("u_3", MemberRole.CONTRIBUTOR), target))
        assertFalse(Membership.canRemove(row("u_5", MemberRole.VIEWER), target))
    }

    @Test
    fun `nobody removes themselves, including the owner`() {
        assertFalse(Membership.canRemove(row("u_1", MemberRole.OWNER), row("u_1", MemberRole.OWNER)))
        assertFalse(Membership.canRemove(row("u_3", MemberRole.CONTRIBUTOR), row("u_3", MemberRole.CONTRIBUTOR)))
    }

    @Test
    fun `an owner row is never removable, whoever asks`() {
        assertFalse(Membership.canRemove(row("u_9", MemberRole.OWNER), row("u_1", MemberRole.OWNER)))
    }

    @Test
    fun `no standing in the family means no removing`() {
        assertFalse(Membership.canRemove(null, row("u_3", MemberRole.CONTRIBUTOR)))
    }

    @Test
    fun `the owner of another family cannot reach into this one`() {
        assertFalse(Membership.canRemove(row("u_1", MemberRole.OWNER, family = "fam_2"), row("u_3", MemberRole.CONTRIBUTOR)))
    }
}
