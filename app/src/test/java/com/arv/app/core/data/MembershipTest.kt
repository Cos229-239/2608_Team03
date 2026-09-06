package com.arv.app.core.data

import com.arv.app.core.data.local.PersonEntity
import com.arv.app.core.model.MemberRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
