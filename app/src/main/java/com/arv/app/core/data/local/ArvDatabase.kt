package com.arv.app.core.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        StoryEntity::class,
        PersonEntity::class,
        RelationshipEntity::class,
        AssetEntity::class,
        TranscriptSegmentEntity::class,
        OutboxEntity::class
    ],
    version = 1,
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
                    .build()
                    .also { instance = it }
            }
    }
}
