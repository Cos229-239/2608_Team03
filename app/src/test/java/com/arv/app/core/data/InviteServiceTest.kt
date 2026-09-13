package com.arv.app.core.data

import com.arv.app.core.data.local.InviteEntity
import com.arv.app.core.data.local.MemberEntity
import com.arv.app.core.model.MemberRole
import com.arv.app.core.remote.InviteRemote
import com.arv.app.core.remote.RemoteRedeem
import com.arv.app.core.remote.RemoteWrite
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InviteServiceTest {

    private val now = 5_000L

    private fun invite(code: String = "K7M2QX", issuer: String = "u_ruth", familyName: String? = "Delaney") =
        InviteEntity(
            code = code,
            familyId = "fam_1",
            issuedByUserId = issuer,
            grantsRole = MemberRole.CONTRIBUTOR,
            createdAt = 1_000L,
            familyName = familyName,
            expiresAt = 1_000L + Invitation.LIFETIME_MILLIS
        )

    private fun owner() =
        MemberEntity(familyId = "fam_1", userId = "u_ruth", role = MemberRole.OWNER, personId = "p_ruth", joinedAt = 100L)

    private fun keeper() =
        MemberEntity(familyId = "fam_1", userId = "u_kev", role = MemberRole.KEEPER, personId = "p_kev", joinedAt = 200L, invitedBy = "u_ruth")

    /** A phone. Holds what it holds and records what was asked of it. */
    private class FakeLocal(
        var live: InviteEntity? = null,
        var localAnswer: Invitation.Result = Invitation.Result.Unknown,
        var me: MemberEntity? = null
    ) : InviteLocal {
        val admitted = mutableListOf<MemberEntity>()

        override suspend fun inviteCodeFor(familyId: String, userId: String, familyName: String?, nowMillis: Long): InviteEntity =
            live ?: fresh(familyId, userId, familyName, nowMillis, "FRESH1").also { live = it }

        override suspend fun replaceInviteCode(familyId: String, userId: String, familyName: String?, nowMillis: Long): InviteEntity {
            live = live?.copy(revokedAt = nowMillis)
            return fresh(familyId, userId, familyName, nowMillis, "FRESH2")
        }

        override suspend fun liveInviteFor(familyId: String, userId: String, nowMillis: Long) =
            live?.takeIf { it.revokedAt == null }

        override suspend fun redeemInvite(typed: String?, userId: String, nowMillis: Long) = localAnswer

        override suspend fun memberRowFor(familyId: String, userId: String) = me

        override suspend fun admitMember(member: MemberEntity) { admitted += member }

        private fun fresh(familyId: String, userId: String, familyName: String?, nowMillis: Long, code: String) =
            InviteEntity(
                code = code,
                familyId = familyId,
                issuedByUserId = userId,
                grantsRole = MemberRole.CONTRIBUTOR,
                createdAt = nowMillis,
                familyName = familyName,
                expiresAt = nowMillis + Invitation.LIFETIME_MILLIS
            )
    }

    /** A server. Answers what it is told to and records every call in order. */
    private class FakeRemote(
        override val available: Boolean = true,
        var registerAnswer: RemoteWrite = RemoteWrite.Done,
        var publishAnswer: RemoteWrite = RemoteWrite.Done,
        var redeemAnswer: RemoteRedeem = RemoteRedeem.Unreachable
    ) : InviteRemote {
        val calls = mutableListOf<String>()

        override suspend fun registerFamily(familyId: String, familyName: String, owner: MemberEntity): RemoteWrite {
            calls += "registerFamily:$familyName:${owner.role}"
            return registerAnswer
        }

        override suspend fun publish(invite: InviteEntity): RemoteWrite {
            calls += "publish:${invite.code}"
            return publishAnswer
        }

        override suspend fun revoke(invite: InviteEntity, nowMillis: Long): RemoteWrite {
            calls += "revoke:${invite.code}"
            return RemoteWrite.Done
        }

        override suspend fun redeem(typed: String): RemoteRedeem {
            calls += "redeem:$typed"
            return redeemAnswer
        }
    }

    // --- redeeming ---

    @Test
    fun `a code this phone knows is answered here and the server is never asked`() {
        runBlocking {
            val member = MemberEntity(familyId = "fam_1", userId = "u_dana", role = MemberRole.CONTRIBUTOR, joinedAt = now, invitedBy = "u_ruth")
            val spent = invite().copy(usedAt = now, usedByUserId = "u_dana")
            val local = FakeLocal(localAnswer = Invitation.Result.Accepted(member, spent))
            val remote = FakeRemote()
            val r = InviteService(local, remote).redeem("k7m-2qx", "u_dana", now)
            assertEquals(InviteService.Joined.In(member, "Delaney"), r)
            assertTrue(remote.calls.isEmpty())
        }
    }

    @Test
    fun `a local refusal is final, even with a server that would have said yes`() {
        runBlocking {
            val local = FakeLocal(localAnswer = Invitation.Result.Expired)
            val remote = FakeRemote(redeemAnswer = RemoteRedeem.Accepted("fam_1", "Delaney", MemberRole.CONTRIBUTOR, "u_ruth", now))
            val r = InviteService(local, remote).redeem("k7m-2qx", "u_dana", now)
            assertEquals(InviteService.Joined.Refused(Invitation.Result.Expired), r)
            assertTrue(remote.calls.isEmpty())
            assertTrue(local.admitted.isEmpty())
        }
    }

    @Test
    fun `a code this phone has never seen goes to the server, and its yes writes a standing here`() {
        runBlocking {
            val local = FakeLocal()
            val remote = FakeRemote(redeemAnswer = RemoteRedeem.Accepted("fam_1", "Delaney", MemberRole.KEEPER, "u_ruth", 7_000L))
            val r = InviteService(local, remote).redeem("k7m-2qx", "u_dana", now)
            val expected = MemberEntity(familyId = "fam_1", userId = "u_dana", role = MemberRole.KEEPER, joinedAt = 7_000L, invitedBy = "u_ruth")
            assertEquals(InviteService.Joined.In(expected, "Delaney"), r)
            assertEquals(listOf(expected), local.admitted)
            assertEquals(listOf("redeem:k7m-2qx"), remote.calls)
        }
    }

    @Test
    fun `the server's refusal comes back as the same answers the phone gives`() {
        runBlocking {
            val local = FakeLocal()
            val remote = FakeRemote(redeemAnswer = RemoteRedeem.Refused(Invitation.Result.AlreadyUsed))
            val r = InviteService(local, remote).redeem("k7m-2qx", "u_dana", now)
            assertEquals(InviteService.Joined.Refused(Invitation.Result.AlreadyUsed), r)
            assertTrue(local.admitted.isEmpty())
        }
    }

    @Test
    fun `no answer from the server changes nothing and says so`() {
        runBlocking {
            val local = FakeLocal()
            val r = InviteService(local, FakeRemote(redeemAnswer = RemoteRedeem.Unreachable)).redeem("k7m-2qx", "u_dana", now)
            assertEquals(InviteService.Joined.Refused(Invitation.Result.Unreachable), r)
            assertTrue(local.admitted.isEmpty())
        }
    }

    @Test
    fun `a build with no server still answers, and admits nobody it cannot vouch for`() {
        runBlocking {
            val local = FakeLocal()
            val r = InviteService(local, InviteRemote.None).redeem("k7m-2qx", "u_dana", now)
            assertEquals(InviteService.Joined.Refused(Invitation.Result.Unreachable), r)
            assertTrue(local.admitted.isEmpty())
        }
    }

    // --- minting ---

    @Test
    fun `an owner's code registers the family first, then travels`() {
        runBlocking {
            val local = FakeLocal(me = owner())
            val remote = FakeRemote()
            val m = InviteService(local, remote).ensureCode("fam_1", "u_ruth", "Delaney", now)
            assertEquals("FRESH1", m.invite.code)
            assertEquals(InviteService.Reach.OtherPhones, m.reach)
            assertEquals(listOf("registerFamily:Delaney:OWNER", "publish:FRESH1"), remote.calls)
        }
    }

    @Test
    fun `a keeper publishes without trying to create a family that is not theirs to create`() {
        runBlocking {
            val local = FakeLocal(me = keeper())
            val remote = FakeRemote()
            val m = InviteService(local, remote).ensureCode("fam_1", "u_kev", "Delaney", now)
            assertEquals(InviteService.Reach.OtherPhones, m.reach)
            assertEquals(listOf("publish:FRESH1"), remote.calls)
        }
    }

    @Test
    fun `a code that could not be published still works here and says so`() {
        runBlocking {
            val local = FakeLocal(me = owner())
            val m = InviteService(local, FakeRemote(publishAnswer = RemoteWrite.Failed)).ensureCode("fam_1", "u_ruth", "Delaney", now)
            assertEquals("FRESH1", m.invite.code)
            assertEquals(InviteService.Reach.ThisPhoneOnly, m.reach)
        }
    }

    @Test
    fun `a family the server refused to register keeps its code on this phone`() {
        runBlocking {
            val local = FakeLocal(me = owner())
            val remote = FakeRemote(registerAnswer = RemoteWrite.Failed)
            val m = InviteService(local, remote).ensureCode("fam_1", "u_ruth", "Delaney", now)
            assertEquals(InviteService.Reach.ThisPhoneOnly, m.reach)
            assertEquals(listOf("registerFamily:Delaney:OWNER"), remote.calls)
        }
    }

    @Test
    fun `no server means no reach and no attempt`() {
        runBlocking {
            val local = FakeLocal(me = owner())
            val m = InviteService(local, InviteRemote.None).ensureCode("fam_1", "u_ruth", "Delaney", now)
            assertEquals(InviteService.Reach.NoServer, m.reach)
        }
    }

    @Test
    fun `no standing here means nothing is published, whatever the server would say`() {
        runBlocking {
            val local = FakeLocal(me = null)
            val remote = FakeRemote()
            val m = InviteService(local, remote).ensureCode("fam_1", "u_ghost", "Delaney", now)
            assertEquals(InviteService.Reach.ThisPhoneOnly, m.reach)
            assertTrue(remote.calls.isEmpty())
        }
    }

    @Test
    fun `replacing a code withdraws the old one everywhere and publishes the new one`() {
        runBlocking {
            val local = FakeLocal(live = invite(), me = owner())
            val remote = FakeRemote()
            val m = InviteService(local, remote).replaceCode("fam_1", "u_ruth", "Delaney", now)
            assertEquals("FRESH2", m.invite.code)
            assertEquals(listOf("revoke:K7M2QX", "registerFamily:Delaney:OWNER", "publish:FRESH2"), remote.calls)
        }
    }
}
