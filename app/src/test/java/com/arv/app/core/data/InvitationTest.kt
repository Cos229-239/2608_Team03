package com.arv.app.core.data

import com.arv.app.core.data.local.InviteEntity
import com.arv.app.core.data.local.MemberEntity
import com.arv.app.core.model.MemberRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InvitationTest {

    private val code = "K7M2QX"

    private fun invite(
        usedAt: Long? = null,
        usedBy: String? = null,
        revokedAt: Long? = null,
        issuer: String = "u_ruth",
        role: MemberRole = MemberRole.CONTRIBUTOR
    ) = InviteEntity(
        code = code,
        familyId = "fam_1",
        issuedByUserId = issuer,
        grantsRole = role,
        createdAt = 1_000L,
        usedAt = usedAt,
        usedByUserId = usedBy,
        revokedAt = revokedAt
    )

    @Test
    fun `a good code writes the inviter into the member row`() {
        val r = Invitation.redeem("k7m-2qx", invite(), null, "u_dana", 5_000L)
        assertTrue(r is Invitation.Result.Accepted)
        val accepted = r as Invitation.Result.Accepted
        assertEquals("u_ruth", accepted.member.invitedBy)
        assertEquals("u_dana", accepted.member.userId)
        assertEquals("fam_1", accepted.member.familyId)
        assertEquals(MemberRole.CONTRIBUTOR, accepted.member.role)
        assertEquals(5_000L, accepted.member.joinedAt)
    }

    @Test
    fun `accepting spends the code and records who spent it`() {
        val r = Invitation.redeem(code, invite(), null, "u_dana", 5_000L)
        val spent = (r as Invitation.Result.Accepted).spent
        assertEquals(5_000L, spent.usedAt)
        assertEquals("u_dana", spent.usedByUserId)
    }

    @Test
    fun `a spent code says so instead of pretending it never existed`() {
        val r = Invitation.redeem(code, invite(usedAt = 2_000L, usedBy = "u_kev"), null, "u_dana", 5_000L)
        assertEquals(Invitation.Result.AlreadyUsed, r)
    }

    @Test
    fun `a forwarded code cannot admit a second person under the same inviter`() {
        val first = Invitation.redeem(code, invite(), null, "u_dana", 5_000L)
        val spent = (first as Invitation.Result.Accepted).spent
        val second = Invitation.redeem(code, spent, null, "u_someone_else", 6_000L)
        assertEquals(Invitation.Result.AlreadyUsed, second)
    }

    @Test
    fun `revoked is not the same answer as used`() {
        val r = Invitation.redeem(code, invite(revokedAt = 3_000L), null, "u_dana", 5_000L)
        assertEquals(Invitation.Result.Revoked, r)
    }

    @Test
    fun `nobody invites themselves`() {
        val r = Invitation.redeem(code, invite(issuer = "u_dana"), null, "u_dana", 5_000L)
        assertEquals(Invitation.Result.YourOwn, r)
    }

    @Test
    fun `already a member is a no-op, not a failure`() {
        val existing = MemberEntity(
            familyId = "fam_1", userId = "u_dana",
            role = MemberRole.KEEPER, joinedAt = 100L
        )
        val r = Invitation.redeem(code, invite(), existing, "u_dana", 5_000L)
        assertEquals(Invitation.Result.AlreadyInThisFamily, r)
    }

    @Test
    fun `half typed is not a code and not an error`() {
        assertEquals(Invitation.Result.NotACode, Invitation.redeem("K7M", invite(), null, "u_dana", 5_000L))
        assertEquals(Invitation.Result.NotACode, Invitation.redeem(null, invite(), null, "u_dana", 5_000L))
    }

    @Test
    fun `well formed but unknown is its own answer`() {
        assertEquals(Invitation.Result.Unknown, Invitation.redeem("ABCDEF", null, null, "u_dana", 5_000L))
    }

    @Test
    fun `the role travels with the invitation, not with the joiner`() {
        val r = Invitation.redeem(code, invite(role = MemberRole.VIEWER), null, "u_dana", 5_000L)
        assertEquals(MemberRole.VIEWER, (r as Invitation.Result.Accepted).member.role)
        assertNull(r.member.personId)
    }
}
