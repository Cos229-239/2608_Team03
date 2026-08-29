package com.arv.app.core.data

import com.arv.app.core.model.Confidence
import com.arv.app.core.model.RelationshipKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The importer had no tests, and it is the piece most able to do quiet damage.
 *
 * Making [ImportedPerson.parentNames] and [ImportedPerson.linkToImporter] exclusive was a
 * one line change that deleted every parent link in a real archive and emptied every tree
 * below them. Ninety four tests passed while it did that, because none of them touched this
 * file. These do.
 */
class FamilyImportTest {

    private fun parse(body: String) =
        FamilyImport.parse("""{"familyName":"Delaney","people":[$body]}""").getOrThrow()

    private fun one(body: String) = parse(body).people.single()

    @Test
    fun `a death with no year still reads as a death`() {
        // A family often knows somebody is gone and not when. Inferring death from a year
        // alone imported those people as living.
        val p = one("""{"displayName":"Gus Delaney","deceased":true}""")
        assertTrue(p.deceased)
        assertNull("no year was invented to carry the flag", p.deathYear)
    }

    @Test
    fun `a death year means deceased without having to say so twice`() {
        val p = one("""{"displayName":"Ruth Delaney","deathYear":1977}""")
        assertTrue(p.deceased)
        assertEquals(1977, p.deathYear)
    }

    @Test
    fun `no year and no flag leaves somebody living`() {
        val p = one("""{"displayName":"Elle Delaney","birthYear":1990}""")
        assertFalse(p.deceased)
    }

    @Test
    fun `parents and a relation label both survive`() {
        // Treating these as alternatives is what wiped the archive. Stating someone's
        // mother must not delete their own link to the person who compiled the file.
        val p = one(
            """{"displayName":"Ray Delaney","relationLabel":"Father",
                "parents":["Opal Delaney","Walter Delaney"]}"""
        )
        assertEquals(listOf("Opal Delaney", "Walter Delaney"), p.parentNames)
        assertEquals(RelationshipKind.PARENT, p.linkToImporter)
    }

    @Test
    fun `an unrecognised grade falls to unverified rather than up`() {
        // Confidence words are free text and differ by whoever compiled the file. The cost
        // of doubting a real ancestor is that somebody confirms them. The cost of trusting
        // an invented one is that they become family.
        assertEquals(
            Confidence.UNVERIFIED,
            one("""{"displayName":"Nobody","confidence":"pretty sure"}""").confidence
        )
    }

    @Test
    fun `a vague label links nothing rather than guessing a generation`() {
        // "3x great-grandmother" says how far up somebody is and never through whom, so
        // there is no honest edge to draw from it.
        assertNull(one("""{"displayName":"Jane Delaney","relationLabel":"3x great-grandmother"}""").linkToImporter)
    }

    @Test
    fun `unambiguous labels do link`() {
        assertEquals(RelationshipKind.PARENT, one("""{"displayName":"A","relationLabel":"Mother"}""").linkToImporter)
        assertEquals(RelationshipKind.SIBLING, one("""{"displayName":"B","relationLabel":"Brother"}""").linkToImporter)
        assertEquals(RelationshipKind.AUNT_UNCLE, one("""{"displayName":"C","relationLabel":"Uncle"}""").linkToImporter)
    }

    @Test
    fun `a file with no people is an error, not an empty archive`() {
        assertTrue(FamilyImport.parse("""{"familyName":"Delaney"}""").isFailure)
    }

    @Test
    fun `a person with no name is not silently dropped`() {
        // Losing a row would lose a relative. They arrive named for what they are so
        // somebody can find and fix them.
        assertEquals("Unnamed", one("""{"birthYear":1900}""").displayName)
    }

    @Test
    fun `a death nobody can date exactly keeps both years`() {
        // "2021 or 2022" is how a family remembers a death. Picking one turns their
        // uncertainty into something the archive appears to vouch for.
        val p = one("""{"displayName":"Gus Delaney","deathYear":2021,"deathYearEnd":2022}""")
        assertEquals(2021, p.deathYear)
        assertEquals(2022, p.deathYearEnd)
        assertTrue(p.deceased)
    }

    @Test
    fun `the same year twice is one year, not a range`() {
        assertNull(one("""{"displayName":"A","deathYear":2021,"deathYearEnd":2021}""").deathYearEnd)
    }

    @Test
    fun `an end earlier than the start is discarded rather than shown backwards`() {
        assertNull(one("""{"displayName":"B","deathYear":2021,"deathYearEnd":2019}""").deathYearEnd)
    }

    @Test
    fun `the note survives the import`() {
        // These were parsed and dropped, so every caveat a compiled history carried was
        // lost on the way in while the confident parts survived.
        assertEquals(
            "Predeceased his parents. No dates in the source.",
            one("""{"displayName":"C","note":"Predeceased his parents. No dates in the source."}""").note
        )
    }

    // --- planning: names must resolve across the same file ---

    private fun planOf(body: String, existing: Map<String, String> = emptyMap()) =
        FamilyImport.plan(
            parsed = parse(body),
            existingIdsByName = existing,
            meId = "p_me",
            meName = "Dana Delaney",
            newId = run { var n = 0; { "p_new${n++}" } }
        )

    @Test
    fun `people defined in the same file can name each other`() {
        // The exact first-import shape that used to produce zero edges: an empty archive
        // and a file whose people only reference one another. One-pass resolution looked
        // names up in a snapshot taken before anybody was created.
        val plan = planOf(
            """{"displayName":"Opal Delaney","spouse":"Walter Delaney"},
               {"displayName":"Walter Delaney"},
               {"displayName":"Ray Delaney","parents":["Opal Delaney","Walter Delaney"]}"""
        )
        val kinds = plan.edges.map { it.kind.name }.sorted()
        assertEquals(listOf("PARENT", "PARENT", "SPOUSE"), kinds)
    }

    @Test
    fun `file order does not decide whether a parent links`() {
        // The child appears before the parent is defined. Two passes make this identical
        // to the other order.
        val plan = planOf(
            """{"displayName":"Ray Delaney","parents":["Opal Delaney"]},
               {"displayName":"Opal Delaney"}"""
        )
        assertEquals(1, plan.edges.count { it.kind == RelationshipKind.PARENT })
    }

    @Test
    fun `the importer's own row is not replanned but their parents still link`() {
        // Skipping the whole row also skipped its edges, so nobody could state their own
        // parents in their own file.
        val plan = planOf(
            """{"displayName":"Dana Delaney","parents":["Ray Delaney"]},
               {"displayName":"Ray Delaney"}"""
        )
        assertTrue(plan.people.none { it.imported.displayName == "Dana Delaney" })
        assertTrue(plan.edges.any {
            it.kind == RelationshipKind.PARENT && it.toId == "p_me"
        })
    }

    @Test
    fun `an existing person keeps their id instead of forking`() {
        val plan = planOf(
            """{"displayName":"Ray Delaney","relationLabel":"Father"}""",
            existing = mapOf("Ray Delaney" to "p_ray")
        )
        assertEquals("p_ray", plan.people.single().personId)
    }

    @Test
    fun `parents and the relation label both become edges`() {
        val plan = planOf(
            """{"displayName":"Ray Delaney","relationLabel":"Father","parents":["Opal Delaney"]},
               {"displayName":"Opal Delaney"}"""
        )
        assertTrue(plan.edges.any { it.kind == RelationshipKind.PARENT && it.toId != "p_me" })
        assertTrue(plan.edges.any { it.kind == RelationshipKind.PARENT && it.toId == "p_me" })
    }

    @Test
    fun `nobody is planned as their own parent or spouse`() {
        val plan = planOf(
            """{"displayName":"Ray Delaney","parents":["Ray Delaney"],"spouse":"Ray Delaney"}"""
        )
        assertTrue(plan.edges.isEmpty())
    }

    @Test
    fun `an unverified relation label arrives marked uncertain`() {
        val plan = planOf(
            """{"displayName":"Gus Delaney","relationLabel":"Uncle","confidence":"unverified"}"""
        )
        assertTrue(plan.edges.single().uncertain)
    }
}
