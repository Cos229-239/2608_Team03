package com.arv.app.core.data

import kotlin.random.Random

/**
 * The code one person reads to another so a family can share an archive.
 *
 * Designed for the way it actually travels: said out loud, on a phone call, by someone
 * who may be eighty and may be writing it on the back of an envelope. Every decision here
 * follows from that.
 *
 *  - No 0/O, no 1/I/L. Those are the pairs people mishear and mistype, and a wrong code
 *    is a dead end with no explanation.
 *  - Six characters, grouped three and three. Long enough that guessing is pointless
 *    (31^6, about 887 million), short enough to hold in your head between hearing it and
 *    writing it down.
 *  - Case and dashes are ignored on the way in. "k7m-2qx", "K7M2QX" and "k 7 m 2 q x" are
 *    the same code, because the person typing it did not choose how it was written down.
 */
object InviteCode {

    /** Deliberately missing 0, O, 1, I and L. See the class note. */
    private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

    private const val LENGTH = 6

    /** What a code looks like once every ambiguity has been stripped out of it. */
    private val CANONICAL = Regex("^[$ALPHABET]{$LENGTH}$")

    /**
     * A new code, in display form.
     *
     * [random] is a parameter so tests can pin it. Production passes the default.
     */
    fun generate(random: Random = Random.Default): String =
        format(buildString { repeat(LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } })

    /**
     * What the person typed, turned into what we can look up, or null if it could never
     * be a code.
     *
     * Returning null rather than throwing because a half-typed code is the normal state of
     * that text field, not an error worth a stack trace.
     */
    fun normalize(input: String?): String? {
        if (input == null) return null
        val stripped = input.uppercase().filter { it.isLetterOrDigit() }
        return if (CANONICAL.matches(stripped)) stripped else null
    }

    /** True when [input] is a code we could look up. */
    fun isValid(input: String?): Boolean = normalize(input) != null

    /** ABC123 becomes ABC-123, which is how it gets read aloud and written down. */
    fun format(code: String): String =
        if (code.length == LENGTH) "${code.take(3)}-${code.drop(3)}" else code
}
