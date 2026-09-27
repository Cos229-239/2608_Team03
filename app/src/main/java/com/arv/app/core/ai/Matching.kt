package com.arv.app.core.ai

/**
 * How a question's words are compared with what the archive holds.
 *
 * One place, used by every librarian. The hive replaced the flat pipeline with a comment
 * promising identical weights, and the place signal was lost on the way: a story saved at
 * "Mom's house" scored nothing for "What happened in Mom's house?" while a recording that
 * said "house" once won. Two copies of the matching were how that happened.
 */
internal object Matching {

    /**
     * Lowercase, with both kinds of apostrophe removed and everything else that is not a
     * letter or a digit turned into a space. A phone keyboard types "Mom’s", a person types
     * "Mom's" or "Moms", and all three are the same words.
     */
    fun normalize(text: String): String =
        text.lowercase()
            .replace("’", "")
            .replace("'", "")
            .replace(NON_WORD, " ")
            .trim()

    /**
     * A normalized word as a whole word, allowing the endings English adds without changing
     * what a word names: flood, floods, flooded, flooding. Not a substring: "house" is not
     * found in "household", and "mom" is not found in "moment".
     */
    fun wordPattern(term: String): Regex =
        Regex("\\b" + Regex.escape(term) + "(s|es|ed|ing)?\\b")

    /** Whether the question names this place in full, in any spelling [normalize] folds. */
    fun namesPlace(parsed: QuestionParse, place: String?): Boolean {
        val label = place?.let(::normalize).orEmpty()
        return label.isNotEmpty() && Regex("\\b" + Regex.escape(label) + "\\b").containsMatchIn(parsed.text)
    }

    /** A question word that is also a word of this place, when the place is not named whole. */
    fun sharedPlaceWord(parsed: QuestionParse, place: String?): String? {
        val label = place?.let(::normalize).orEmpty()
        if (label.isEmpty()) return null
        return parsed.terms.firstOrNull { term -> parsed.patternFor(term).containsMatchIn(label) }
    }

    private val NON_WORD = Regex("[^a-z0-9]+")
}

/**
 * How firmly a memory answers the question, strongest first. Ranking goes by this before
 * score, and the answer says which one it is, because a family has to be able to tell a
 * fact the archive holds from a guess the librarian made.
 */
enum class MatchStrength {
    /** Something the family stated about the memory: who told it, who it is about, its years, its place. */
    STATED,

    /** Words the family wrote on it: its title, its tags, part of its place. */
    WRITTEN,

    /** Only words somebody said in the recording. It might be related, and the answer says so. */
    SPOKEN
}
