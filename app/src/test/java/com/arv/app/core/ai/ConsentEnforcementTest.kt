package com.arv.app.core.ai

import com.arv.app.core.model.Confidence
import com.arv.app.core.model.LibrarianScope
import com.arv.app.core.model.MemberRole
import com.arv.app.core.model.Person
import com.arv.app.core.model.ProfileState
import com.arv.app.core.model.Story
import com.arv.app.core.model.StoryKind
import com.arv.app.core.model.Visibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The person page has promised "their memories stay restricted until a consent record
 * exists" since the flag was added, and nothing enforced it: consentGranted and
 * postMortemOk were stored, displayed, and never read by a single decision. These pin the
 * sentence to behavior.
 */
class ConsentEnforcementTest {

    private val fam = "fam_1"
    private fun viewer(userId: String = "u_other", persons: Set<String> = emptySet()) =
        Viewer(userId = userId, role = MemberRole.CONTRIBUTOR, familyId = fam, personIds = persons)

    private fun keeper() = Viewer(userId = "u_keeper", role = MemberRole.KEEPER, familyId = fam)

    private fun told(narrator: String) = Story(
        storyId = "s_1", familyId = fam, title = "Kitchen interview", kind = StoryKind.AUDIO,
        visibility = Visibility.FAMILY, narratorIds = listOf(narrator), createdBy = "u_recorder"
    )

    private fun person(
        id: String,
        consent: Boolean = false,
        state: ProfileState = ProfileState.LIVING,
        postMortemOk: Boolean = false,
        confidence: Confidence = Confidence.FAMILY_TOLD,
        source: String? = null
    ) = Person(
        personId = id, displayName = "Ruth Delaney", consentGranted = consent,
        state = state, postMortemOk = postMortemOk, confidence = confidence, source = source
    )

    @Test
    fun `a living narrator with no consent record blocks the family`() {
        val ruth = person("p_ruth")
        assertFalse(MemoryAccess.canRead(told("p_ruth"), viewer(), listOf(ruth)))
    }

    @Test
    fun `consent granted opens it back up`() {
        val ruth = person("p_ruth", consent = true)
        assertTrue(MemoryAccess.canRead(told("p_ruth"), viewer(), listOf(ruth)))
    }

    @Test
    fun `the recorder still reads what they recorded`() {
        // They hold the recording and the responsibility of getting the consent. Locking
        // them out would make the consent impossible to go and get.
        val ruth = person("p_ruth")
        assertTrue(MemoryAccess.canRead(told("p_ruth"), viewer(userId = "u_recorder"), listOf(ruth)))
    }

    @Test
    fun `the narrator reads their own voice`() {
        val ruth = person("p_ruth")
        assertTrue(MemoryAccess.canRead(told("p_ruth"), viewer(persons = setOf("p_ruth")), listOf(ruth)))
    }

    @Test
    fun `a keeper role is not a substitute for a person's answer`() {
        // The same stance PRIVATE takes: if this ever passes, the product is broken.
        val ruth = person("p_ruth")
        assertFalse(MemoryAccess.canRead(told("p_ruth"), keeper(), listOf(ruth)))
    }

    @Test
    fun `a recorded post-mortem decision unblocks the dead`() {
        val ruth = person("p_ruth", state = ProfileState.MEMORIAL, postMortemOk = true)
        assertTrue(MemoryAccess.canRead(told("p_ruth"), viewer(), listOf(ruth)))
    }

    @Test
    fun `a dead narrator with no decision on file stays restricted`() {
        val ruth = person("p_ruth", state = ProfileState.MEMORIAL)
        assertFalse(MemoryAccess.canRead(told("p_ruth"), viewer(), listOf(ruth)))
    }

    @Test
    fun `public record about the dead needs nobody's permission`() {
        // "Dead we can use public information without consent": documented, sourced,
        // deceased. The person page already said this in words.
        val ruth = person(
            "p_ruth", state = ProfileState.MEMORIAL,
            confidence = Confidence.DOCUMENTED, source = "Obituary"
        )
        assertTrue(MemoryAccess.canRead(told("p_ruth"), viewer(), listOf(ruth)))
    }

    @Test
    fun `a narrator nowhere in the archive blocks nothing`() {
        // No person row means nothing is known either way; the visibility gate alone
        // decides. Blocking on absence would dark half an imported archive.
        assertTrue(MemoryAccess.canRead(told("p_unknown"), viewer(), emptyList()))
    }

    @Test
    fun `the librarian is gated the same way`() {
        val ruth = person("p_ruth")
        val out = MemoryAccess.partition(
            listOf(told("p_ruth")), viewer(), LibrarianScope.FAMILY, listOf(ruth)
        )
        assertTrue(out.usable.isEmpty())
        assertEquals(1, out.withheldCount)
    }
}
