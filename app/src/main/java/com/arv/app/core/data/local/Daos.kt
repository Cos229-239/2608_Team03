package com.arv.app.core.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.arv.app.core.model.UploadState
import kotlinx.coroutines.flow.Flow

@Dao
interface StoryDao {

    @Query("SELECT * FROM stories WHERE familyId = :familyId ORDER BY createdAt DESC")
    fun observeRecent(familyId: String): Flow<List<StoryEntity>>

    @Query(
        """
        SELECT * FROM stories
        WHERE familyId = :familyId AND eraStart IS NOT NULL
        ORDER BY eraStart ASC, createdAt ASC
        """
    )
    fun observeDated(familyId: String): Flow<List<StoryEntity>>

    @Query("SELECT * FROM stories WHERE familyId = :familyId AND eraStart IS NULL")
    fun observeUndated(familyId: String): Flow<List<StoryEntity>>

    @Query("SELECT * FROM stories WHERE storyId = :storyId")
    fun observeById(storyId: String): Flow<StoryEntity?>

    /**
     * Records rather than recordings: marriage certificates, death records, ship
     * manifests, the postcard in somebody's box.
     *
     * Sorted so the ones nobody has found yet (assetCount = 0) sit at the top. A family
     * archive that only shows what has already been scanned hides the work still to do,
     * and the work still to do is how the box in the attic eventually gets opened.
     *
     * PHOTO_SET belongs here alongside DOCUMENT. The postcard of the ship someone's mother
     * came over on is a record in every sense a family means it, and filtering on DOCUMENT
     * alone meant every photograph anyone added saved correctly and then appeared nowhere.
     */
    @Query(
        """
        SELECT * FROM stories
        WHERE familyId = :familyId AND kind IN ('DOCUMENT', 'PHOTO_SET')
        ORDER BY (assetCount > 0) ASC, eraStart ASC, title ASC
        """
    )
    fun observeDocuments(familyId: String): Flow<List<StoryEntity>>

    @Query("SELECT COUNT(*) FROM stories WHERE familyId = :familyId")
    suspend fun count(familyId: String): Int

    /**
     * Unfiltered. Callers MUST run [com.arv.app.core.ai.MemoryAccess]
     * over the result before anything reaches a screen or a model.
     */
    @Query("SELECT * FROM stories WHERE familyId = :familyId ORDER BY createdAt DESC")
    suspend fun all(familyId: String): List<StoryEntity>

    /**
     * Keyword fallback for when embeddings aren't available.
     * Search must degrade, never fail. See docs/SPEC.md §6.
     */
    @Query(
        """
        SELECT DISTINCT s.* FROM stories s
        LEFT JOIN assets a ON a.storyId = s.storyId
        LEFT JOIN transcript_segments t ON t.assetId = a.assetId
        WHERE s.familyId = :familyId
          AND (s.title LIKE '%' || :query || '%'
               OR s.tags LIKE '%' || :query || '%'
               OR t.text LIKE '%' || :query || '%'
               OR a.ocrText LIKE '%' || :query || '%')
        ORDER BY s.createdAt DESC
        """
    )
    suspend fun searchKeyword(familyId: String, query: String): List<StoryEntity>

    @Upsert
    suspend fun upsert(story: StoryEntity)

    @Upsert
    suspend fun upsertAll(stories: List<StoryEntity>)

    @Delete
    suspend fun delete(story: StoryEntity)
}

@Dao
interface PersonDao {

    @Query("SELECT * FROM people WHERE familyId = :familyId ORDER BY displayName ASC")
    fun observeAll(familyId: String): Flow<List<PersonEntity>>

    /** One-shot read for the librarian's name detection. */
    @Query("SELECT * FROM people WHERE familyId = :familyId")
    suspend fun all(familyId: String): List<PersonEntity>

    @Query("SELECT * FROM people WHERE personId = :personId")
    suspend fun byId(personId: String): PersonEntity?

    /** Total recorded audio for one person, in ms. Drives the "hours preserved" meter. */
    @Query(
        """
        SELECT COALESCE(SUM(durationMs), 0) FROM stories
        WHERE familyId = :familyId AND narratorIds LIKE '%' || :personId || '%'
        """
    )
    fun observeRecordedMsFor(familyId: String, personId: String): Flow<Long>

    @Upsert
    suspend fun upsert(person: PersonEntity)

    @Upsert
    suspend fun upsertAll(people: List<PersonEntity>)
}

@Dao
interface RelationshipDao {

    @Query("SELECT * FROM relationships WHERE familyId = :familyId")
    fun observeAll(familyId: String): Flow<List<RelationshipEntity>>

    /** One-shot read, for writing the whole archive out to a file. */
    @Query("SELECT * FROM relationships WHERE familyId = :familyId")
    suspend fun observeAllOnce(familyId: String): List<RelationshipEntity>

    /** Both directions, because a tree is walked from whichever person you are looking at. */
    @Query(
        """
        SELECT * FROM relationships
        WHERE familyId = :familyId
          AND (fromPersonId = :personId OR toPersonId = :personId)
        """
    )
    fun observeFor(familyId: String, personId: String): Flow<List<RelationshipEntity>>

    @Upsert
    suspend fun upsert(relationship: RelationshipEntity)

    @Upsert
    suspend fun upsertAll(relationships: List<RelationshipEntity>)

    @Delete
    suspend fun delete(relationship: RelationshipEntity)
}

@Dao
interface AssetDao {

    @Query("SELECT * FROM assets WHERE storyId = :storyId ORDER BY createdAt ASC")
    fun observeForStory(storyId: String): Flow<List<AssetEntity>>

    /**
     * Every asset in the family, so a list screen can offer a working play button on each
     * row from one query instead of one query per card.
     */
    @Query("SELECT * FROM assets WHERE familyId = :familyId ORDER BY createdAt ASC")
    fun observeForFamily(familyId: String): Flow<List<AssetEntity>>

    /** One-shot read, for writing the whole archive out to a file. */
    @Query("SELECT * FROM assets WHERE familyId = :familyId ORDER BY createdAt ASC")
    suspend fun forFamily(familyId: String): List<AssetEntity>

    @Query("SELECT * FROM assets WHERE uploadState IN (:states) ORDER BY createdAt ASC")
    fun observeByUploadState(states: List<UploadState>): Flow<List<AssetEntity>>

    @Query("SELECT * FROM assets WHERE assetId = :assetId")
    suspend fun byId(assetId: String): AssetEntity?

    @Query("UPDATE assets SET uploadState = :state, remotePath = :remotePath WHERE assetId = :assetId")
    suspend fun markUploadState(assetId: String, state: UploadState, remotePath: String?)

    @Upsert
    suspend fun upsert(asset: AssetEntity)
}

@Dao
interface TranscriptDao {

    @Query("SELECT * FROM transcript_segments WHERE assetId = :assetId ORDER BY startMs ASC")
    fun observeForAsset(assetId: String): Flow<List<TranscriptSegmentEntity>>

    /** One-shot read, for writing the whole archive out to a file. */
    @Query("SELECT * FROM transcript_segments WHERE assetId = :assetId ORDER BY startMs ASC")
    suspend fun forAssetOnce(assetId: String): List<TranscriptSegmentEntity>

    /** Every transcript line for a story, across its assets. The librarian reads these. */
    @Query(
        """
        SELECT t.* FROM transcript_segments t
        JOIN assets a ON t.assetId = a.assetId
        WHERE a.storyId = :storyId
        ORDER BY t.startMs ASC
        """
    )
    suspend fun forStory(storyId: String): List<TranscriptSegmentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(segments: List<TranscriptSegmentEntity>)

    /** Keeps the machine's original text so provenance survives the correction. */
    @Query(
        """
        UPDATE transcript_segments
        SET originalText = COALESCE(originalText, text),
            text = :newText,
            humanVerified = 1
        WHERE id = :segmentId
        """
    )
    suspend fun correct(segmentId: Long, newText: String)

    @Query("DELETE FROM transcript_segments WHERE assetId = :assetId")
    suspend fun clearForAsset(assetId: String)
}

@Dao
interface OutboxDao {

    @Query("SELECT * FROM outbox ORDER BY createdAt ASC LIMIT :limit")
    suspend fun nextBatch(limit: Int = 20): List<OutboxEntity>

    @Query("SELECT COUNT(*) FROM outbox")
    fun observePendingCount(): Flow<Int>

    @Insert
    suspend fun enqueue(op: OutboxEntity): Long

    @Query("UPDATE outbox SET attempts = attempts + 1, lastError = :error WHERE id = :id")
    suspend fun recordFailure(id: Long, error: String)

    @Query("DELETE FROM outbox WHERE id = :id")
    suspend fun complete(id: Long)
}
