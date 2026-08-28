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
        assertNull(one("""{"displayName":"Jane Harbour","relationLabel":"3x great-grandmother"}""").linkToImporter)
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
}
