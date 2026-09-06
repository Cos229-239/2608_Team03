package com.arv.app.core.data.local

import com.arv.app.core.model.MemberRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MemberMappingTest {

    @Test
    fun `a mapped member keeps every field`() {
        val m = MemberEntity(
            familyId = "fam_1",
            userId = "u_2",
            role = MemberRole.CONTRIBUTOR,
            personId = "p_2",
            branchRootPersonId = "p_root",
            joinedAt = 900L,
            invitedBy = "u_1"
        ).toDomain()
        assertEquals("fam_1", m.familyId)
        assertEquals("u_2", m.userId)
        assertEquals(MemberRole.CONTRIBUTOR, m.role)
        assertEquals("p_2", m.personId)
        assertEquals("p_root", m.branchRootPersonId)
        assertEquals(900L, m.joinedAt)
        assertEquals("u_1", m.invitedBy)
    }

    @Test
    fun `a member not yet placed in the tree maps with no person`() {
        val m = MemberEntity(familyId = "fam_1", userId = "u_3", role = MemberRole.VIEWER, joinedAt = 1L).toDomain()
        assertNull(m.personId)
        assertNull(m.branchRootPersonId)
        assertNull(m.invitedBy)
    }
}
