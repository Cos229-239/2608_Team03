package com.arv.app.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tree tab was a flat list with no search and no lens, which is fine at nine people
 * and useless at ninety. The importer can bring in a compiled history in one go, so ninety
 * is not hypothetical.
 *
 * Both filters are pure and shared with the feed, so the two screens cannot drift on what
 * "Ruth's side" means or on which names a search looks at.
 */
class PeopleFilterTest {

    private fun person(
        id: String,
        name: String,
        aka: List<String> = emptyList(),
        birthPlace: String? = null,
        linkedUserId: String? = null
    ) = Person(
        personId = id,
        displayName = name,
        alsoKnownAs = aka,
        birthPlace = birthPlace,
        linkedUserId = linkedUserId
    )

    private val me = person("p_me", "Dana Delaney", linkedUserId = "u_dana")
    private val mother = person("p_mum", "Ruth Delaney", aka = listOf("Ruthie"))
    private val father = person("p_dad", "Ray Delaney")
    private val opal = person("p_opal", "Miss Opal", birthPlace = "Bastad, Sweden")

    private val everyone = listOf(me, mother, father, opal)

    private val edges = listOf(
        Relationship("p_mum", "p_me", RelationshipKind.PARENT),
        Relationship("p_dad", "p_me", RelationshipKind.PARENT),
        Relationship("p_opal", "p_mum", RelationshipKind.PARENT)
    )

    // --- search ---

    @Test
    fun `an empty query is not a filter`() {
        assertEquals(everyone, everyone.filter { it.matchesSearch("") })
        assertEquals(everyone, everyone.filter { it.matchesSearch("   ") })
    }

    @Test
    fun `three letters beat scrolling`() {
        val hits = everyone.filter { it.matchesSearch("del") }
        assertEquals(3, hits.size)
        assertTrue(hits.none { it.personId == "p_opal" })
    }

    @Test
    fun `a nickname finds somebody the family never calls by their full name`() {
        assertTrue(mother.matchesSearch("Ruthie"))
        // The point of including alsoKnownAs: a maiden name is very often the only name
        // an older relative is remembered by.
        assertTrue(mother.matchesSearch("ruthie"))
    }

    @Test
    fun `a birthplace finds the branch that came from somewhere`() {
        assertTrue(opal.matchesSearch("sweden"))
        assertFalse(mother.matchesSearch("sweden"))
    }

    @Test
    fun `case never matters, because nobody types a relative's name carefully`() {
        assertTrue(mother.matchesSearch("RUTH"))
        assertTrue(mother.matchesSearch("ruth"))
    }

    // --- lens ---

    @Test
    fun `the whole family is everybody`() {
        assertEquals(everyone, everyone.underLens(FamilyLens.Whole, "p_me", edges))
    }

    @Test
    fun `just me is only me`() {
        val mine = everyone.underLens(FamilyLens("Just me", mine = true), "p_me", edges)
        assertEquals(listOf(me), mine)
    }

    @Test
    fun `a side reaches the line it is named after and not the other one`() {
        val mothers = everyone.underLens(FamilyLens("Ruth's side", parentId = "p_mum"), "p_me", edges)
        val ids = mothers.map { it.personId }
        assertTrue("her mother is on that side", "p_opal" in ids)
        assertTrue("she is on it", "p_mum" in ids)
        assertTrue("and so am I", "p_me" in ids)
        assertFalse("her father is not", "p_dad" in ids)
    }

    @Test
    fun `a lens naming somebody who is gone falls back to the whole family`() {
        // Removing a parent edge removes the side it named, and that can happen while
        // somebody is looking at it. Filtering by a ghost would show an empty screen with
        // no explanation.
        val options = FamilyLens.optionsFor("p_me", everyone, edges)
        val ghost = FamilyLens("Someone's side", parentId = "p_gone")
        assertEquals(FamilyLens.Whole, FamilyLens.resolve(ghost, options))
    }

    @Test
    fun `options offer only parents the archive has actually heard of`() {
        val options = FamilyLens.optionsFor("p_me", everyone, edges)
        val labels = options.map { it.label }
        assertEquals("Whole family", labels.first())
        assertEquals("Just me", labels.last())
        assertTrue("Ruth's side" in labels)
        assertTrue("Ray's side" in labels)
        assertEquals("no side for a parent nobody recorded", 4, options.size)
    }

    @Test
    fun `somebody not yet placed in the tree still gets the whole family`() {
        assertEquals(listOf(FamilyLens.Whole), FamilyLens.optionsFor(null, everyone, edges))
    }

    // --- short names, shared so two screens cannot disagree ---

    @Test
    fun `an honorific is part of the name, not a title to strip`() {
        assertEquals("Ruth", mother.shortName())
        assertEquals("Miss Opal", opal.shortName())
        assertEquals("Ruth", person("x", "Ruth").shortName())
    }
}
