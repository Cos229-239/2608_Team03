package com.arv.app.core.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the migration does not lose anybody's recordings.
 *
 * This database can hold the only copy of a dead person's voice, which is why
 * [ArvDatabase] deliberately has no destructive fallback: a schema bump must never be able
 * to quietly wipe an archive. That decision is only half a strategy without this test,
 * because it means a wrong migration hard-bricks the app on launch instead, and the only
 * way out is clearing app data, which is the exact loss the refusal was protecting against.
 *
 * So this opens a real version 1 database, writes real rows into it, runs the migration for
 * real, and reads the rows back. `runMigrationsAndValidate` additionally compares the
 * resulting schema against the exported 2.json, so a migration that succeeds but produces
 * the wrong shape still fails here rather than on somebody's phone.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ArvDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrate1To2_keepsEveryRowAndAddsTheNewColumns() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                """
                INSERT INTO people
                  (personId, familyId, displayName, alsoKnownAs, birthYear, deathYear,
                   birthPlace, relationLabel, linkedUserId, state, memoryStewardUserId,
                   consentGranted, postMortemOk, updatedAt)
                VALUES
                  ('p_1', 'fam_1', 'Ruth Delaney', '', 1931, NULL, 'Chicago', 'Grandmother',
                   'u_1', 'LIVING', NULL, 1, 0, 100)
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO stories
                  (storyId, familyId, title, kind, area, narratorIds, subjectPersonIds,
                   eraStart, eraEnd, eraPrecision, placeLabel, tags, visibility, aiUsePolicy,
                   provenance, sharedWithUserIds, restricted, durationMs, assetCount,
                   transcriptStatus, uploadState, primaryAssetId, createdBy, createdAt,
                   updatedAt)
                VALUES
                  ('s_1', 'fam_1', 'The night the levee broke', 'AUDIO', 'STORIES', '', '',
                   1953, 1953, 'EXACT', NULL, '', 'FAMILY', 'SUMMARY_OK',
                   'AUTHENTIC_RECORDING', '', 0, 724000, 1, 'READY', 'LOCAL_ONLY', 'a_1',
                   'u_1', 100, 100)
                """.trimIndent()
            )
        }

        // Runs MIGRATION_1_2 and checks the result against the exported 2.json.
        val db = helper.runMigrationsAndValidate(
            TEST_DB, 2, true, ArvDatabase.MIGRATION_1_2
        )

        db.query("SELECT displayName, confidence, source, verifiedAt FROM people").use { c ->
            assertTrue("the person survived the migration", c.moveToFirst())
            assertEquals("Ruth Delaney", c.getString(0))
            // Anyone already in an archive was put there by a relative, which is a real
            // claim and distinct both from a document and from unchecked research.
            assertEquals("FAMILY_TOLD", c.getString(1))
            assertTrue("source starts empty", c.isNull(2))
            assertTrue("nobody has been verified yet", c.isNull(3))
            assertEquals("exactly one person, nothing duplicated", 1, c.count)
        }

        db.query("SELECT title, durationMs, branchRootPersonId FROM stories").use { c ->
            assertTrue("the recording survived the migration", c.moveToFirst())
            assertEquals("The night the levee broke", c.getString(0))
            // The thing that actually matters: the audio is still findable afterwards.
            assertEquals(724000L, c.getLong(1))
            assertTrue("no story is scoped to a branch by accident", c.isNull(2))
            assertEquals(1, c.count)
        }
    }

    @Test
    fun migrate2To3_keepsEveryRowAndAddsTheUncertainDeathColumns() {
        helper.createDatabase(TEST_DB, 2).use { db ->
            db.execSQL(
                """
                INSERT INTO people
                  (personId, familyId, displayName, alsoKnownAs, birthYear, deathYear,
                   birthPlace, relationLabel, linkedUserId, state, memoryStewardUserId,
                   consentGranted, postMortemOk, updatedAt, confidence, source, verifiedAt)
                VALUES
                  ('p_1', 'fam_1', 'Ruth Delaney', '', 1931, 2004, 'Chicago', 'Grandmother',
                   'u_1', 'MEMORIAL', NULL, 1, 0, 100, 'DOCUMENTED', 'Death certificate', 200)
                """.trimIndent()
            )
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB, 3, true, ArvDatabase.MIGRATION_2_3
        )

        db.query(
            "SELECT displayName, deathYear, deathYearEnd, note, source FROM people"
        ).use { c ->
            assertTrue("the person survived the migration", c.moveToFirst())
            assertEquals("Ruth Delaney", c.getString(0))
            // The dated death is untouched. Adding room for an uncertain one must not
            // disturb the people whose dates were never in doubt.
            assertEquals(2004, c.getInt(1))
            assertTrue("no death range invented for an exact date", c.isNull(2))
            assertTrue("no note invented", c.isNull(3))
            assertEquals("Death certificate", c.getString(4))
            assertEquals("exactly one person, nothing duplicated", 1, c.count)
        }
    }

    @Test
    fun migrate3To4_addsPromptsAndLeavesEverythingElseAlone() {
        helper.createDatabase(TEST_DB, 3).use { db ->
            db.execSQL(
                """
                INSERT INTO people
                  (personId, familyId, displayName, alsoKnownAs, birthYear, deathYear,
                   birthPlace, relationLabel, linkedUserId, state, memoryStewardUserId,
                   consentGranted, postMortemOk, updatedAt, confidence, source, verifiedAt,
                   deathYearEnd, note)
                VALUES
                  ('p_1', 'fam_1', 'Ruth Delaney', '', 1931, 2004, 'Chicago', 'Grandmother',
                   'u_1', 'MEMORIAL', NULL, 1, 0, 100, 'DOCUMENTED', 'Death certificate',
                   200, NULL, NULL)
                """.trimIndent()
            )
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB, 4, true, ArvDatabase.MIGRATION_3_4
        )

        // The new table exists and starts empty. Questions arrive by seeding, not by a
        // schema bump inventing them.
        db.query("SELECT COUNT(*) FROM prompts").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("no questions invented by the migration", 0, c.getInt(0))
        }

        // A prompt round-trips through every column the entity declares.
        db.execSQL(
            """
            INSERT INTO prompts
              (promptId, familyId, text, category, targetPersonId, origin, rationale,
               status, answeredStoryId, createdAt, updatedAt)
            VALUES
              ('q_1', 'fam_1', 'Who taught you to cook?', 'Food', NULL, 'LIBRARY',
               'Often opens into migration stories', 'SUGGESTED', NULL, 1, 1)
            """.trimIndent()
        )
        db.query("SELECT text, category, status, origin FROM prompts WHERE promptId = 'q_1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Who taught you to cook?", c.getString(0))
            assertEquals("Food", c.getString(1))
            assertEquals("SUGGESTED", c.getString(2))
            assertEquals("LIBRARY", c.getString(3))
        }

        // Adding a table must not disturb anyone already in the archive.
        db.query("SELECT displayName, deathYear FROM people").use { c ->
            assertTrue("the person survived the migration", c.moveToFirst())
            assertEquals("Ruth Delaney", c.getString(0))
            assertEquals(2004, c.getInt(1))
            assertEquals("exactly one person, nothing duplicated", 1, c.count)
        }
    }

    @Test
    fun migrate4To5_addsMembersAndLeavesEverythingElseAlone() {
        helper.createDatabase(TEST_DB, 4).use { db ->
            db.execSQL(
                """
                INSERT INTO people
                  (personId, familyId, displayName, alsoKnownAs, birthYear, deathYear,
                   birthPlace, relationLabel, linkedUserId, state, memoryStewardUserId,
                   consentGranted, postMortemOk, updatedAt, confidence, source, verifiedAt,
                   deathYearEnd, note)
                VALUES
                  ('p_1', 'fam_1', 'Ruth Delaney', '', 1931, 2004, 'Chicago', 'Grandmother',
                   'u_1', 'MEMORIAL', NULL, 1, 0, 100, 'DOCUMENTED', 'Death certificate',
                   200, NULL, NULL)
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO prompts
                  (promptId, familyId, text, category, targetPersonId, origin, rationale,
                   status, answeredStoryId, createdAt, updatedAt)
                VALUES
                  ('q_1', 'fam_1', 'Who taught you to cook?', 'Food', NULL, 'LIBRARY',
                   NULL, 'SAVED', NULL, 1, 1)
                """.trimIndent()
            )
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB, 5, true, ArvDatabase.MIGRATION_4_5
        )

        // The new table exists and starts empty. Standing in a family is written by the
        // code that creates or joins one, never invented by a schema bump.
        db.query("SELECT COUNT(*) FROM members").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("no members invented by the migration", 0, c.getInt(0))
        }

        // A member round-trips through every column, and a member with no profile yet is
        // a legal row: that is how an invited account looks before it is placed in the tree.
        db.execSQL(
            """
            INSERT INTO members
              (familyId, userId, role, personId, branchRootPersonId, joinedAt, invitedBy)
            VALUES
              ('fam_1', 'u_1', 'OWNER', 'p_1', NULL, 100, NULL),
              ('fam_1', 'u_2', 'VIEWER', NULL, 'p_1', 200, 'u_1')
            """.trimIndent()
        )
        db.query("SELECT role, personId, invitedBy FROM members WHERE userId = 'u_1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("OWNER", c.getString(0))
            assertEquals("p_1", c.getString(1))
            assertTrue("nobody invited the owner", c.isNull(2))
        }
        db.query("SELECT role, personId, invitedBy FROM members WHERE userId = 'u_2'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("VIEWER", c.getString(0))
            assertTrue("not placed in the tree yet", c.isNull(1))
            assertEquals("u_1", c.getString(2))
        }

        // The key is family and user together: one account has one standing per family
        // and can stand in more than one family.
        db.execSQL(
            "INSERT OR REPLACE INTO members (familyId, userId, role, personId, joinedAt) VALUES ('fam_1', 'u_1', 'KEEPER', 'p_1', 100)"
        )
        db.execSQL(
            "INSERT INTO members (familyId, userId, role, personId, joinedAt) VALUES ('fam_2', 'u_1', 'CONTRIBUTOR', NULL, 300)"
        )
        db.query("SELECT COUNT(*) FROM members WHERE familyId = 'fam_1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("replacing a role does not duplicate the member", 2, c.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM members WHERE userId = 'u_1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("one account, two families", 2, c.getInt(0))
        }

        // Adding a table must not disturb anyone, or any question, already in the archive.
        db.query("SELECT displayName, deathYear FROM people").use { c ->
            assertTrue("the person survived the migration", c.moveToFirst())
            assertEquals("Ruth Delaney", c.getString(0))
            assertEquals(2004, c.getInt(1))
            assertEquals("exactly one person, nothing duplicated", 1, c.count)
        }
        db.query("SELECT status FROM prompts WHERE promptId = 'q_1'").use { c ->
            assertTrue("the saved prompt survived the migration", c.moveToFirst())
            assertEquals("SAVED", c.getString(0))
        }
    }

    @Test
    fun migrate5To6_letsConsentBeAnAnswerAndKeepsTheYesAlreadyGiven() {
        helper.createDatabase(TEST_DB, 5).use { db ->
            db.execSQL(
                """
                INSERT INTO people
                  (personId, familyId, displayName, alsoKnownAs, birthYear, deathYear,
                   birthPlace, relationLabel, linkedUserId, state, memoryStewardUserId,
                   consentGranted, postMortemOk, updatedAt, confidence, source, verifiedAt,
                   deathYearEnd, note)
                VALUES
                  ('p_1', 'fam_1', 'Ruth Delaney', '', 1931, NULL, 'Chicago', 'Grandmother',
                   'u_1', 'LIVING', NULL, 1, 1, 100, 'FAMILY_TOLD', NULL, NULL, NULL, NULL)
                """.trimIndent()
            )
            db.execSQL(
                "INSERT INTO members (familyId, userId, role, personId, joinedAt) VALUES ('fam_1', 'u_1', 'OWNER', 'p_1', 100)"
            )
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB, 6, true, ArvDatabase.MIGRATION_5_6
        )

        // A yes given before the answer had a date keeps being a yes, and is not a no.
        db.query(
            "SELECT consentGranted, postMortemOk, consentDeclined, consentDecidedAt, consentMethod, consentRecordedBy FROM people WHERE personId = 'p_1'"
        ).use { c ->
            assertTrue("the person survived the migration", c.moveToFirst())
            assertEquals("the yes survived", 1, c.getInt(0))
            assertEquals("the post-mortem yes survived", 1, c.getInt(1))
            assertEquals("nobody said no on their behalf", 0, c.getInt(2))
            assertTrue("no date invented", c.isNull(3))
            assertTrue("no method invented", c.isNull(4))
            assertTrue("no recorder invented", c.isNull(5))
        }

        // A full answer round-trips through the new columns.
        db.execSQL(
            "UPDATE people SET consentGranted = 0, consentDeclined = 1, consentDecidedAt = 500, consentMethod = 'IN_PERSON', consentRecordedBy = 'u_1' WHERE personId = 'p_1'"
        )
        db.query("SELECT consentDeclined, consentDecidedAt, consentMethod, consentRecordedBy FROM people WHERE personId = 'p_1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
            assertEquals(500L, c.getLong(1))
            assertEquals("IN_PERSON", c.getString(2))
            assertEquals("u_1", c.getString(3))
        }

        // Adding columns to people must not disturb the membership beside it.
        db.query("SELECT role FROM members WHERE familyId = 'fam_1' AND userId = 'u_1'").use { c ->
            assertTrue("the member survived the migration", c.moveToFirst())
            assertEquals("OWNER", c.getString(0))
        }
    }

    @Test
    fun migrate6To7_addsInvitesWithoutDisturbingWhoIsAlreadyInTheFamily() {
        helper.createDatabase(TEST_DB, 6).use { db ->
            db.execSQL(
                """
                INSERT INTO people
                  (personId, familyId, displayName, alsoKnownAs, birthYear, deathYear,
                   deathYearEnd, birthPlace, relationLabel, linkedUserId, state,
                   memoryStewardUserId, consentGranted, postMortemOk, confidence, source,
                   verifiedAt, note, updatedAt, consentDeclined, consentDecidedAt,
                   consentMethod, consentRecordedBy)
                VALUES
                  ('p_1', 'fam_1', 'Ruth Delaney', '', 1931, NULL, NULL, 'Chicago',
                   'Grandmother', 'u_1', 'LIVING', NULL, 1, 1, 'FAMILY_TOLD', NULL,
                   NULL, NULL, 100, 0, NULL, NULL, NULL)
                """.trimIndent()
            )
            db.execSQL(
                "INSERT INTO members (familyId, userId, role, personId, joinedAt, invitedBy) " +
                    "VALUES ('fam_1', 'u_1', 'OWNER', 'p_1', 100, NULL)"
            )
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB, 7, true, ArvDatabase.MIGRATION_6_7
        )

        // Adding a table beside the family must not touch the family.
        db.query("SELECT displayName, consentGranted FROM people WHERE personId = 'p_1'").use { c ->
            assertTrue("the person survived the migration", c.moveToFirst())
            assertEquals("Ruth Delaney", c.getString(0))
            assertEquals("the yes survived", 1, c.getInt(1))
        }
        db.query("SELECT role FROM members WHERE familyId = 'fam_1' AND userId = 'u_1'").use { c ->
            assertTrue("the member survived the migration", c.moveToFirst())
            assertEquals("OWNER", c.getString(0))
        }

        // The owner mints a code, and it arrives unspent.
        db.execSQL(
            "INSERT INTO invites (code, familyId, issuedByUserId, grantsRole, createdAt) " +
                "VALUES ('K7M2QX', 'fam_1', 'u_1', 'CONTRIBUTOR', 200)"
        )
        db.query("SELECT issuedByUserId, grantsRole, usedAt, usedByUserId, revokedAt FROM invites WHERE code = 'K7M2QX'").use { c ->
            assertTrue("the invitation was written", c.moveToFirst())
            assertEquals("u_1", c.getString(0))
            assertEquals("CONTRIBUTOR", c.getString(1))
            assertTrue("a new code is unspent", c.isNull(2))
            assertTrue("and nobody has used it", c.isNull(3))
            assertTrue("and it is not revoked", c.isNull(4))
        }

        // Spending it records both sides of the pairing, which is the point of keeping
        // the row instead of deleting it.
        db.execSQL("UPDATE invites SET usedAt = 300, usedByUserId = 'u_2' WHERE code = 'K7M2QX'")
        db.query("SELECT usedAt, usedByUserId FROM invites WHERE code = 'K7M2QX'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(300L, c.getLong(0))
            assertEquals("u_2", c.getString(1))
        }

        // The code is the primary key, so a second invitation cannot reuse one. Without
        // this a forwarded code could be re-minted under somebody else and the trail
        // would say they let a person in.
        var rejected = false
        try {
            db.execSQL(
                "INSERT INTO invites (code, familyId, issuedByUserId, grantsRole, createdAt) " +
                    "VALUES ('K7M2QX', 'fam_1', 'u_2', 'VIEWER', 400)"
            )
        } catch (e: android.database.sqlite.SQLiteConstraintException) {
            rejected = true
        }
        assertTrue("a code cannot be issued twice", rejected)
    }

    @Test
    fun migrate7To8_letsACodeSayWhichFamilyItOpensWithoutInventingOne() {
        helper.createDatabase(TEST_DB, 7).use { db ->
            db.execSQL(
                "INSERT INTO people (personId, familyId, displayName, alsoKnownAs, state, " +
                    "consentGranted, postMortemOk, updatedAt, confidence, source, verifiedAt, " +
                    "deathYearEnd, note, consentDeclined, consentDecidedAt, consentMethod, " +
                    "consentRecordedBy) " +
                    "VALUES ('p_1', 'fam_1', 'Ruth Delaney', '', 'LIVING', 1, 0, 100, " +
                    "'FAMILY_TOLD', NULL, NULL, NULL, NULL, 0, NULL, NULL, NULL)"
            )
            // A code minted before the column existed. It was never told a name.
            db.execSQL(
                "INSERT INTO invites (code, familyId, issuedByUserId, grantsRole, createdAt) " +
                    "VALUES ('K7M2QX', 'fam_1', 'u_1', 'CONTRIBUTOR', 200)"
            )
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB, 8, true, ArvDatabase.MIGRATION_7_8
        )

        db.query("SELECT displayName FROM people WHERE personId = 'p_1'").use { c ->
            assertTrue("the person survived the migration", c.moveToFirst())
            assertEquals("Ruth Delaney", c.getString(0))
        }

        // The old code still works and still says it does not know. Backfilling a name
        // here would be the archive making one up, which is the one thing it does not do.
        db.query("SELECT familyId, familyName FROM invites WHERE code = 'K7M2QX'").use { c ->
            assertTrue("the invitation survived the migration", c.moveToFirst())
            assertEquals("fam_1", c.getString(0))
            assertTrue("a code minted before the column knows no name", c.isNull(1))
        }

        // A code minted after it does.
        db.execSQL(
            "INSERT INTO invites (code, familyId, issuedByUserId, grantsRole, createdAt, familyName) " +
                "VALUES ('P4RT9Y', 'fam_1', 'u_1', 'CONTRIBUTOR', 300, 'The Delaney family')"
        )
        db.query("SELECT familyName FROM invites WHERE code = 'P4RT9Y'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("The Delaney family", c.getString(0))
        }
    }

    @Test
    fun migrate8To9_givesAPersonAFaceAndLeavesTheirRecordAlone() {
        helper.createDatabase(TEST_DB, 8).use { db ->
            db.execSQL(
                "INSERT INTO people (personId, familyId, displayName, alsoKnownAs, state, " +
                    "consentGranted, postMortemOk, updatedAt, confidence, source, verifiedAt, " +
                    "deathYearEnd, note, consentDeclined, consentDecidedAt, consentMethod, " +
                    "consentRecordedBy) " +
                    "VALUES ('p_1', 'fam_1', 'Ruth Delaney', '', 'LIVING', 1, 0, 100, " +
                    "'FAMILY_TOLD', NULL, NULL, NULL, NULL, 0, NULL, NULL, NULL)"
            )
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB, 9, true, ArvDatabase.MIGRATION_8_9
        )

        // A column about how somebody is displayed must not disturb what is recorded
        // about them. Consent especially: this archive's whole claim rests on it.
        db.query(
            "SELECT displayName, consentGranted, portraitAssetId FROM people WHERE personId = 'p_1'"
        ).use { c ->
            assertTrue("the person survived the migration", c.moveToFirst())
            assertEquals("Ruth Delaney", c.getString(0))
            assertEquals("the yes survived", 1, c.getInt(1))
            assertTrue("nobody has chosen a face yet", c.isNull(2))
        }

        // And a face can be pointed at afterwards. The column holds an asset id rather
        // than a path, so the photograph keeps the story that says who may see it.
        db.execSQL("UPDATE people SET portraitAssetId = 'a_1' WHERE personId = 'p_1'")
        db.query("SELECT portraitAssetId FROM people WHERE personId = 'p_1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("a_1", c.getString(0))
        }

        // Clearing it goes back to initials rather than to a broken image.
        db.execSQL("UPDATE people SET portraitAssetId = NULL WHERE personId = 'p_1'")
        db.query("SELECT portraitAssetId FROM people WHERE personId = 'p_1'").use { c ->
            assertTrue(c.moveToFirst())
            assertTrue("cleared back to no face", c.isNull(0))
        }
    }

    @Test
    fun migrate9To10_movesAFaceOntoThePersonAndKeepsWhereItCameFrom() {
        helper.createDatabase(TEST_DB, 9).use { db ->
            db.execSQL(
                "INSERT INTO people (personId, familyId, displayName, alsoKnownAs, state, " +
                    "consentGranted, postMortemOk, updatedAt, confidence, source, verifiedAt, " +
                    "deathYearEnd, note, consentDeclined, consentDecidedAt, consentMethod, " +
                    "consentRecordedBy, portraitAssetId) " +
                    "VALUES ('p_1', 'fam_1', 'Ruth Delaney', '', 'LIVING', 1, 0, 100, " +
                    "'FAMILY_TOLD', NULL, NULL, NULL, NULL, 0, NULL, NULL, NULL, 'a_old')"
            )
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB, 10, true, ArvDatabase.MIGRATION_9_10
        )

        // Additive, so the row that pointed at an asset under 9 is untouched. It simply
        // has no path yet and draws initials until somebody picks a face again, which is
        // the honest outcome of changing our minds about the design.
        db.query(
            "SELECT displayName, consentGranted, portraitPath, portraitAssetId " +
                "FROM people WHERE personId = 'p_1'"
        ).use { c ->
            assertTrue("the person survived the migration", c.moveToFirst())
            assertEquals("Ruth Delaney", c.getString(0))
            assertEquals("the yes survived", 1, c.getInt(1))
            assertTrue("no face yet", c.isNull(2))
            assertEquals("and where the old one came from was kept", "a_old", c.getString(3))
        }

        // An uploaded face carries a path and no source record.
        db.execSQL(
            "UPDATE people SET portraitPath = '/f/portraits/x.jpg', portraitAssetId = NULL " +
                "WHERE personId = 'p_1'"
        )
        db.query("SELECT portraitPath, portraitAssetId FROM people WHERE personId = 'p_1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("/f/portraits/x.jpg", c.getString(0))
            assertTrue("an upload came from no record", c.isNull(1))
        }

        // One taken from the archive carries both.
        db.execSQL(
            "UPDATE people SET portraitPath = '/f/portraits/y.jpg', portraitAssetId = 'a_1' " +
                "WHERE personId = 'p_1'"
        )
        db.query("SELECT portraitPath, portraitAssetId FROM people WHERE personId = 'p_1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("/f/portraits/y.jpg", c.getString(0))
            assertEquals("a_1", c.getString(1))
        }
    }

    private companion object {
        const val TEST_DB = "migration-test"
    }
}