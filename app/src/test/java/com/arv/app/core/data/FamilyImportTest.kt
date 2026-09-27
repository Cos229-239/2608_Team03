package com.arv.app.core.data

import com.arv.app.core.data.local.PersonEntity
import com.arv.app.core.data.local.toDomain
import com.arv.app.core.model.Confidence
import com.arv.app.core.model.ConsentMethod
import com.arv.app.core.model.ProfileState
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

    // --- importing again: what the archive already holds ---

    /**
     * Somebody the family has worked on since the first import: a documented record somebody
     * checked, consent written down, a steward named, an account linked, a face chosen, and a
     * copy on the family's server.
     */
    private fun held(updatedAt: Long = 500L) = PersonEntity(
        personId = "p_ray",
        familyId = "fam_delaney",
        displayName = "Ray Delaney",
        birthYear = 1947,
        relationLabel = "Father",
        confidence = Confidence.DOCUMENTED,
        source = "Birth certificate",
        verifiedAt = 300L,
        linkedUserId = "u_ray",
        memoryStewardUserId = "u_dana",
        consentGranted = true,
        postMortemOk = true,
        consentDecidedAt = 400L,
        consentMethod = ConsentMethod.IN_PERSON,
        consentRecordedBy = "u_dana",
        portraitPath = "portraits/p_ray.jpg",
        portraitAssetId = "a_wedding",
        updatedAt = updatedAt,
        syncedAt = updatedAt,
        refusedAt = 450L
    )

    @Test
    fun `a re-import corrects what the file says and keeps everything it cannot say`() {
        // The importer rebuilt this row from the file, which reset every column the file has
        // no field for, and sync then gave the emptied copy to the whole family.
        val before = held()
        val after = FamilyImport.merge(
            before,
            one("""{"displayName":"Ray Delaney","birthYear":1948,"confidence":"verified"}"""),
            nowMillis = 1_000L
        )
        assertEquals(before.copy(birthYear = 1948, updatedAt = 1_000L), after)
    }

    @Test
    fun `a re-import is stamped past the version it replaces`() {
        // This phone's clock is behind the one that wrote Ray's current row. Stamped with its
        // own time, the import would lose to that row on the next pull and disappear.
        val after = FamilyImport.merge(
            held(updatedAt = 5_000L),
            one("""{"displayName":"Ray Delaney","birthYear":1948,"confidence":"verified"}"""),
            nowMillis = 1_000L
        )
        assertEquals(5_001L, after.updatedAt)
    }

    @Test
    fun `importing the same file again changes nothing, so sync has nothing to send`() {
        val before = held()
        val after = FamilyImport.merge(
            before,
            one(
                """{"displayName":"Ray Delaney","birthYear":1947,"relationLabel":"Father",
                    "confidence":"verified","source":"Birth certificate"}"""
            ),
            nowMillis = 1_000L
        )
        assertEquals(before, after)
    }

    @Test
    fun `a family-told row neither overwrites a documented person nor lowers their grade`() {
        // The grade vouches for the whole person. Taking the file's year under it would pass
        // a memory off as a document, and taking the file's grade would quietly undo a check.
        val before = held()
        val after = FamilyImport.merge(
            before,
            one(
                """{"displayName":"Ray Delaney","birthYear":1950,"deathYear":2020,
                    "note":"Mom remembers 1950","confidence":"user_reported"}"""
            ),
            nowMillis = 1_000L
        )
        assertEquals(before, after)
    }

    @Test
    fun `leaving a death out of the file does not bring anybody back`() {
        // A file can say somebody died and has no way to say they did not, so what it leaves
        // out stays, and so does a birthplace somebody else recorded.
        val before = held().copy(state = ProfileState.MEMORIAL, deathYear = 2019, birthPlace = "Tampa")
        val after = FamilyImport.merge(
            before,
            one("""{"displayName":"Ray Delaney","birthYear":1948,"confidence":"verified"}"""),
            nowMillis = 1_000L
        )
        assertEquals(before.copy(birthYear = 1948, updatedAt = 1_000L), after)
    }

    @Test
    fun `a file that dates a death replaces the whole of the old answer`() {
        // The two years are one answer. Keeping the old end would turn a correction to one
        // exact year back into the range it corrected.
        val before = held().copy(state = ProfileState.MEMORIAL, deathYear = 2019, deathYearEnd = 2020)
        val after = FamilyImport.merge(
            before,
            one("""{"displayName":"Ray Delaney","deathYear":2019,"confidence":"verified"}"""),
            nowMillis = 1_000L
        )
        assertEquals(2019, after.deathYear)
        assertNull(after.deathYearEnd)
    }

    @Test
    fun `a better-graded row replaces the record instead of vouching for what it left out`() {
        // Unchecked research had Ray dead. The corrected row is backed by his birth certificate
        // and says nothing about a death. Kept under the new grade, the old death would make
        // him public record, and his recordings would stop waiting for his answer.
        val before = PersonEntity(
            personId = "p_ray",
            familyId = "fam_delaney",
            displayName = "Ray Delaney",
            birthYear = 1947,
            deathYear = 1990,
            birthPlace = "Tampa",
            relationLabel = "Father",
            state = ProfileState.MEMORIAL,
            confidence = Confidence.UNVERIFIED,
            source = "Somebody's online tree",
            memoryStewardUserId = "u_dana",
            portraitPath = "portraits/p_ray.jpg",
            updatedAt = 500L,
            syncedAt = 500L
        )
        val after = FamilyImport.merge(
            before,
            one(
                """{"displayName":"Ray Delaney","birthYear":1948,
                    "confidence":"verified","source":"Birth certificate"}"""
            ),
            nowMillis = 1_000L
        )
        assertEquals(
            before.copy(
                birthYear = 1948,
                deathYear = null,
                birthPlace = null,
                state = ProfileState.LIVING,
                confidence = Confidence.DOCUMENTED,
                source = "Birth certificate",
                updatedAt = 1_000L
            ),
            after
        )
        assertTrue(after.toDomain().consentRestricts)
    }

    @Test
    fun `a no written down for somebody survives the re-import that makes them public record`() {
        // The worst thing the rebuild did. Once Gus is documented and dead he needs no consent
        // decision, so the family's no is the only thing still holding his recordings back,
        // and the rebuilt row had forgotten it.
        val before = PersonEntity(
            personId = "p_gus",
            familyId = "fam_delaney",
            displayName = "Gus Delaney",
            deathYear = 1990,
            state = ProfileState.MEMORIAL,
            confidence = Confidence.UNVERIFIED,
            consentDeclined = true,
            consentDecidedAt = 400L,
            consentMethod = ConsentMethod.ON_THEIR_BEHALF,
            consentRecordedBy = "u_dana",
            updatedAt = 500L,
            syncedAt = 500L
        )
        val after = FamilyImport.merge(
            before,
            one("""{"displayName":"Gus Delaney","deathYear":1990,"confidence":"verified","source":"Obituary"}"""),
            nowMillis = 1_000L
        )
        assertTrue(after.toDomain().isPublicRecord)
        assertTrue(after.toDomain().consentRestricts)
    }

    @Test
    fun `a dispute the file raises lands on an unverified person`() {
        // Unverified and conflicted weigh the same. Neither has been checked, but only one
        // tells whoever checks next that the sources disagree.
        val after = FamilyImport.merge(
            PersonEntity(
                personId = "p_jane",
                familyId = "fam_delaney",
                displayName = "Jane Delaney",
                confidence = Confidence.UNVERIFIED,
                updatedAt = 500L
            ),
            one("""{"displayName":"Jane Delaney","confidence":"conflicting sources"}"""),
            nowMillis = 1_000L
        )
        assertEquals(Confidence.CONFLICTED, after.confidence)
    }
}
