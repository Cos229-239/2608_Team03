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

    private companion object {
        const val TEST_DB = "migration-test"
    }
}