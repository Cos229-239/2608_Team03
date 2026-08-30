package com.arv.app.feature.timeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gap cards are the reason the timeline exists: they name the decades nobody has
 * recorded yet, while the person who lived through them can still be asked. A gap that
 * under-reports is worse than no gap at all, because it tells a family the record is more
 * complete than it is.
 *
 * The regression these tests pin: the original returned the decade BEFORE each hole and
 * the caller rendered a single card for `decade + 10`, so a 1950 to 1990 hole advertised
 * only the 1960s and stayed silent about the 1970s and 1980s.
 */
class FindGapsTest {

    @Test
    fun `no decades means no gaps`() {
        assertTrue(findGaps(emptyList()).isEmpty())
    }

    @Test
    fun `a single decade cannot have a gap after it`() {
        assertTrue(findGaps(listOf(1950)).isEmpty())
    }

    @Test
    fun `consecutive decades have no gap`() {
        assertTrue(findGaps(listOf(1950, 1960, 1970)).isEmpty())
    }

    @Test
    fun `a one decade hole is reported`() {
        assertEquals(mapOf(1950 to listOf(1960)), findGaps(listOf(1950, 1970)))
    }

    @Test
    fun `a long hole reports every missing decade, not just the first`() {
        // 1950 to 1990 is missing the 60s, 70s and 80s. Reporting only the 60s was the bug.
        assertEquals(
            mapOf(1950 to listOf(1960, 1970, 1980)),
            findGaps(listOf(1950, 1990))
        )
    }

    @Test
    fun `separate holes are reported separately`() {
        // 1900 to 1930 misses the 10s and 20s, 1930 to 1950 misses the 40s,
        // 1950 to 1970 misses the 60s. Three distinct holes, all reported.
        assertEquals(
            mapOf(
                1900 to listOf(1910, 1920),
                1930 to listOf(1940),
                1950 to listOf(1960)
            ),
            findGaps(listOf(1900, 1930, 1950, 1970))
        )
    }

    @Test
    fun `every reported decade really is absent from the input`() {
        val present = listOf(1910, 1960, 1990)
        val reported = findGaps(present).values.flatten()
        assertTrue(reported.none { it in present })
        assertEquals(listOf(1920, 1930, 1940, 1950, 1970, 1980), reported.sorted())
    }
}
