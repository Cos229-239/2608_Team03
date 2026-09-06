package com.arv.app.core.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        StoryEntity::class,
        PersonEntity::class,
        RelationshipEntity::class,
        AssetEntity::class,
        TranscriptSegmentEntity::class,
        OutboxEntity::class,
        PromptEntity::class,
        MemberEntity::class
    ],
    version = 6,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class ArvDatabase : RoomDatabase() {

    abstract fun promptDao(): PromptDao
    abstract fun memberDao(): MemberDao
    abstract fun storyDao(): StoryDao
    abstract fun personDao(): PersonDao
    abstract fun relationshipDao(): RelationshipDao
    abstract fun assetDao(): AssetDao
    abstract fun transcriptDao(): TranscriptDao
    abstract fun outboxDao(): OutboxDao

    companion object {
        @Volatile private var instance: ArvDatabase? = null

        /**
         * Adds provenance to people, and a branch root to stories.
         *
         * Purely additive: new nullable columns and one with a default, so every existing
         * row survives untouched. Nothing is rewritten and nothing is dropped, which is the
         * only kind of migration this database should ever get lightly. `confidence`
         * backfills to FAMILY_TOLD because anyone already in an archive was put there by a
         * relative, and that is a real claim, distinct from a document and distinct from
         * unchecked research.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE people ADD COLUMN confidence TEXT NOT NULL DEFAULT 'FAMILY_TOLD'"
                )
                db.execSQL("ALTER TABLE people ADD COLUMN source TEXT")
                db.execSQL("ALTER TABLE people ADD COLUMN verifiedAt INTEGER")
                db.execSQL("ALTER TABLE stories ADD COLUMN branchRootPersonId TEXT")
            }
        }

        /**
         * Lets a person carry an uncertain death and the words the record used.
         *
         * Additive again: two nullable columns, no rewrites, no drops. `deathYearEnd` makes
         * "2021 or 2022" storable as the range it is instead of forcing a guess, and `note`
         * stops the importer discarding everything a compiled history said about somebody
         * while keeping the parts it was sure about.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE people ADD COLUMN deathYearEnd INTEGER")
                db.execSQL("ALTER TABLE people ADD COLUMN note TEXT")
            }
        }

        /**
         * Gives the prompt library somewhere to keep its questions.
         *
         * Additive: one new table, nothing existing is touched. Prompts carry state
         * because a question already answered should stop being asked, and one somebody
         * deliberately skipped should not resurface as if the archive forgot.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS prompts (
                        promptId TEXT NOT NULL PRIMARY KEY,
                        familyId TEXT NOT NULL,
                        text TEXT NOT NULL,
                        category TEXT NOT NULL,
                        targetPersonId TEXT,
                        origin TEXT NOT NULL,
                        rationale TEXT,
                        status TEXT NOT NULL,
                        answeredStoryId TEXT,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_prompts_familyId ON prompts(familyId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_prompts_targetPersonId ON prompts(targetPersonId)")
            }
        }

        /**
         * Gives each account a standing in each family it belongs to.
         *
         * Additive: one new table, nothing existing is touched. Before this the only member
         * a device knew about was whoever created the archive, and their role was a
         * constant in code. The row makes the role a fact that can differ per person, which
         * is what invitations need before they can exist at all.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS members (
                        familyId TEXT NOT NULL,
                        userId TEXT NOT NULL,
                        role TEXT NOT NULL,
                        personId TEXT,
                        branchRootPersonId TEXT,
                        joinedAt INTEGER NOT NULL,
                        invitedBy TEXT,
                        PRIMARY KEY(familyId, userId)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_members_userId ON members(userId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_members_personId ON members(personId)")
            }
        }

        /**
         * Lets a person's consent be an answer instead of a flag.
         *
         * Additive: four nullable-or-defaulted columns on people, nothing rewritten. The
         * two booleans that existed said yes or nothing. These say who wrote the answer
         * down, when, how it reached them, and whether the answer was no, which the old
         * shape could not store at all and so could never stop asking for.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE people ADD COLUMN consentDeclined INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE people ADD COLUMN consentDecidedAt INTEGER")
                db.execSQL("ALTER TABLE people ADD COLUMN consentMethod TEXT")
                db.execSQL("ALTER TABLE people ADD COLUMN consentRecordedBy TEXT")
            }
        }

        fun get(context: Context): ArvDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ArvDatabase::class.java,
                    "arv.db"
                )
                    // No destructive migration. This database holds recordings that may be
                    // the only copy of someone's voice; losing it to a schema bump is not
                    // an acceptable failure mode. Write real migrations.
                    .addMigrations(
                        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6
                    )
                    .build()
                    .also { instance = it }
            }
    }
}
