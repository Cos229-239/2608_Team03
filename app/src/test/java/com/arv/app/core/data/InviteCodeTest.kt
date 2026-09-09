package com.arv.app.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class InviteCodeTest {

    @Test
    fun `generated codes never contain a character people mishear`() {
        val forbidden = setOf('0', 'O', '1', 'I', 'L')
        repeat(500) {
            val code = InviteCode.generate()
            code.filter { it != '-' }.forEach {
                assertFalse("generated $code containing $it", it in forbidden)
            }
        }
    }

    @Test
    fun `generated codes are six characters shown as two groups of three`() {
        val code = InviteCode.generate(Random(42))
        assertEquals(7, code.length)
        assertEquals('-', code[3])
        assertTrue(InviteCode.isValid(code))
    }

    @Test
    fun `however it was written down it is the same code`() {
        val canonical = InviteCode.normalize("K7M2QX")
        assertEquals(canonical, InviteCode.normalize("k7m2qx"))
        assertEquals(canonical, InviteCode.normalize("K7M-2QX"))
        assertEquals(canonical, InviteCode.normalize("k 7 m 2 q x"))
        assertEquals(canonical, InviteCode.normalize("  K7M2QX  "))
    }

    @Test
    fun `a half typed code is not an error, it is just not a code yet`() {
        assertNull(InviteCode.normalize("K7M"))
        assertNull(InviteCode.normalize(""))
        assertNull(InviteCode.normalize(null))
        assertFalse(InviteCode.isValid("K7M2"))
    }

    @Test
    fun `a code carrying a character we never mint is rejected rather than guessed at`() {
        assertNull(InviteCode.normalize("K7M2QO"))
        assertNull(InviteCode.normalize("K7M2Q0"))
        assertNull(InviteCode.normalize("K7M2QI"))
    }

    @Test
    fun `the same seed gives the same code, so this is testable at all`() {
        assertEquals(InviteCode.generate(Random(7)), InviteCode.generate(Random(7)))
    }
}
