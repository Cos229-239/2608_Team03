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
        val trimmed = text.trim()

        if (trimmed.matches(Regex("""\d{4}"""))) {
            val year = trimmed.toInt()
            return Parsed(year, year, EraPrecision.EXACT)
        }

        if (trimmed.matches(Regex("""\d{4}\s*(to|-|–)\s*\d{4}"""))) {
            val years = Regex("""\d{4}""")
                .findAll(trimmed)
                .map { it.value.toInt() }
                .toList()

            return Parsed(
                start = years[0],
                end = years[1],
                precision = EraPrecision.RANGE
            )
        }

        return Parsed(null, null, EraPrecision.UNKNOWN)
    }
}
