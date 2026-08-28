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

    private companion object {
        const val TEST_DB = "migration-test"
    }
}