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
        OutboxEntity::class
    ],
    version = 3,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class ArvDatabase : RoomDatabase() {

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
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { instance = it }
            }
    }
}
