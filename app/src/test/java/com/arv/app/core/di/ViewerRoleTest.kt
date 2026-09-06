package com.arv.app.core.di

import com.arv.app.core.model.MemberRole
import com.arv.app.core.session.ActiveSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/**
 * The viewer is built in one place, and this is the contract of that place: the role is
 * the session's, the sample family is only ever a fallback for nobody, and an account
 * with no archive open is never quietly handed the demo.
 */
class ViewerRoleTest {

    @Before fun reset() = ActiveSession.clearAuth()
    @After fun tidy() = ActiveSession.clearAuth()

    @Test
    fun `nobody signed in at all is the sample family's owner`() {
        val v = ServiceLocator.viewer
        assertEquals(ServiceLocator.DEMO_FAMILY_ID, v.familyId)
        assertEquals(ServiceLocator.DEMO_USER_ID, v.userId)
        assertEquals(MemberRole.OWNER, v.role)
    }

    @Test
    fun `an open archive gives the viewer its stored role`() {
        ActiveSession.set("fam_1", "u_1", "The Nilssons", MemberRole.KEEPER)
        val v = ServiceLocator.viewer
        assertEquals("fam_1", v.familyId)
        assertEquals("u_1", v.userId)
        assertEquals(MemberRole.KEEPER, v.role)
    }

    @Test
    fun `refreshing the role from the member row is what the viewer sees next`() {
        ActiveSession.set("fam_1", "u_1", "The Nilssons", MemberRole.OWNER)
        ActiveSession.setRole(MemberRole.VIEWER)
        assertEquals(MemberRole.VIEWER, ServiceLocator.viewer.role)
    }

    @Test
    fun `an account with no archive open is refused the sample family`() {
        ActiveSession.setAuth("uid_abc", "someone@example.com")
        assertThrows(IllegalStateException::class.java) { ServiceLocator.familyId }
        assertEquals("the account is still who they are", "uid_abc", ServiceLocator.userId)
    }

    @Test
    fun `leaving an archive drops the role with it`() {
        ActiveSession.set("fam_1", "u_1", "The Nilssons", MemberRole.KEEPER)
        ActiveSession.clear()
        assertEquals(null, ActiveSession.role)
        assertEquals(MemberRole.OWNER, ServiceLocator.viewer.role)
    }
}
