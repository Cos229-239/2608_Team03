package com.arv.app.core.data

import com.arv.app.core.model.EraPrecision

/**
 * Reads a year, or a range, out of whatever somebody typed.
 *
 * Accepts "1958-1964", "1958 to 1964", "summer 1953", or "1953". Anything it cannot read
 * becomes UNKNOWN rather than a guess, because a wrong year in an archive outlives the
 * person who could have corrected it.
 *
 * One parser, shared by saving and editing. Two copies of this rule would eventually
 * disagree, and then the same text would mean different years depending on which screen
 * it was typed into.
 */
object EraText {

    data class Parsed(val start: Int?, val end: Int?, val precision: EraPrecision)

    fun parse(text: String): Parsed {
        val years = Regex("\\d{4}").findAll(text).map { it.value.toInt() }.toList()
        return when {
            years.isEmpty() -> Parsed(null, null, EraPrecision.UNKNOWN)
            years.size == 1 -> Parsed(years[0], years[0], EraPrecision.EXACT)
            else -> Parsed(years.min(), years.max(), EraPrecision.RANGE)
        }
    }
}
