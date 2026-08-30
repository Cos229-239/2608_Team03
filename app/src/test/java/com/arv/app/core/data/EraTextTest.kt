package com.arv.app.core.data

import com.arv.app.core.model.EraPrecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** One parser for saving and editing, so the same text always means the same years. */
class EraTextTest {

    @Test
    fun `a single year is exact`() {
        val e = EraText.parse("1953")
        assertEquals(1953, e.start)
        assertEquals(1953, e.end)
        assertEquals(EraPrecision.EXACT, e.precision)
    }

    @Test
    fun `words around the year do not spoil it`() {
        assertEquals(1953, EraText.parse("summer of 1953, I think").start)
    }

    @Test
    fun `two years become a range in either order`() {
        val e = EraText.parse("1964 to 1958")
        assertEquals(1958, e.start)
        assertEquals(1964, e.end)
        assertEquals(EraPrecision.RANGE, e.precision)
    }

    @Test
    fun `garbage becomes unknown, never a guess`() {
        for (text in listOf("!!!", "-953", "abc", "0", "")) {
            val e = EraText.parse(text)
            assertNull(text, e.start)
            assertEquals(text, EraPrecision.UNKNOWN, e.precision)
        }
    }

    @Test
    fun `a future year is a typo, not a memory`() {
        val e = EraText.parse("3026", thisYear = 2026)
        assertNull(e.start)
        assertEquals(EraPrecision.UNKNOWN, e.precision)
    }

    @Test
    fun `a real year beside a future one survives alone`() {
        val e = EraText.parse("1953 to 2953", thisYear = 2026)
        assertEquals(1953, e.start)
        assertEquals(1953, e.end)
        assertEquals(EraPrecision.EXACT, e.precision)
    }

    @Test
    fun `this year itself is fine`() {
        assertEquals(2026, EraText.parse("2026", thisYear = 2026).start)
    }
}
