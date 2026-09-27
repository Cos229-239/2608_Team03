package com.arv.app.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** How a question's words meet the archive's. Small rules, each one a real miss once. */
class MatchingTest {

    @Test
    fun `curly and straight apostrophes, and none at all, are the same words`() {
        assertEquals("moms house", Matching.normalize("Mom’s house"))
        assertEquals("moms house", Matching.normalize("Mom's  House"))
        assertEquals("moms house", Matching.normalize("moms house"))
    }

    @Test
    fun `a word is matched whole, with only the endings that do not change what it names`() {
        val house = Matching.wordPattern("house")
        assertTrue(house.containsMatchIn("the whole house smelled like biscuits"))
        assertTrue(house.containsMatchIn("two houses down"))
        assertFalse(house.containsMatchIn("household chores"))
        assertFalse(Matching.wordPattern("mom").containsMatchIn("wait a moment"))
        assertTrue(Matching.wordPattern("flood").containsMatchIn("the flooding started"))
    }

    @Test
    fun `a place counts as named only when the whole of it is in the question`() {
        val asked = QuestionParse.of("What happened in Mom's house?", emptyList())
        assertTrue(Matching.namesPlace(asked, "Mom’s House"))
        assertFalse(Matching.namesPlace(asked, "Grandma's house"))
        assertEquals("house", Matching.sharedPlaceWord(asked, "Grandma's house"))
        assertNull(Matching.sharedPlaceWord(asked, "The farm"))
    }

    @Test
    fun `words that ask are not words to search for`() {
        assertEquals(listOf("moms", "house"), QuestionParse.of("What happened in Mom's house?", emptyList()).terms)
    }
}
