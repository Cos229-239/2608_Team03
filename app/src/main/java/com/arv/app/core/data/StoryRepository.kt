package com.arv.app.core.data

import com.arv.app.core.ai.TranscriptionService
import com.arv.app.core.data.local.ArvDatabase
import com.arv.app.core.data.local.AssetEntity
import com.arv.app.core.data.local.OutboxEntity
import com.arv.app.core.data.local.PersonEntity
import com.arv.app.core.data.local.RelationshipEntity
import com.arv.app.core.data.local.StoryEntity
import com.arv.app.core.data.local.TranscriptSegmentEntity
import com.arv.app.core.model.RelationshipKind
import com.arv.app.core.data.local.toDomain
import com.arv.app.core.model.ArchiveArea
import com.arv.app.core.model.AssetType
import com.arv.app.core.model.OutboxOp
import java.io.File
import java.util.UUID
import com.arv.app.core.model.AiUsePolicy
import com.arv.app.core.model.EraPrecision
import com.arv.app.core.model.Person
import com.arv.app.core.model.Provenance
import com.arv.app.core.model.Story
import com.arv.app.core.model.StoryKind
import com.arv.app.core.model.TranscriptStatus
import com.arv.app.core.model.UploadState
import com.arv.app.core.model.Visibility
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Reads always come from Room, never from the network.
 *
 * The phone is the source of truth until an upload is confirmed (docs/SPEC.md §3).
 * A sync worker writes into Room; the UI observes Room. Nothing in the UI layer ever
 * waits on a request, which is what makes the offline case ordinary instead of special.
 */
class StoryRepository(
    private val db: ArvDatabase
) {

    fun observeRecent(familyId: String): Flow<List<Story>> =
        db.storyDao().observeRecent(familyId).map { rows -> rows.map { it.toDomain() } }

    fun observePeople(familyId: String): Flow<List<Person>> =
        db.personDao().observeAll(familyId).map { rows -> rows.map { it.toDomain() } }

    fun observeById(storyId: String): Flow<Story?> =
        db.storyDao().observeById(storyId).map { it?.toDomain() }

    fun observePendingSyncCount(): Flow<Int> = db.outboxDao().observePendingCount()

    /**
     * Timeline grouping. Undated material is returned under a null key rather than being
     * dropped or guessed into a decade. An archive that invents dates is worse than one
     * with holes in it.
     */
    fun observeByDecade(familyId: String): Flow<Map<Int?, List<Story>>> =
        combine(
            db.storyDao().observeDated(familyId),
            db.storyDao().observeUndated(familyId)
        ) { dated, undated ->
            val grouped = dated
                .map { it.toDomain() }
                .groupBy { it.decade }
                .toSortedMap(compareBy { it ?: Int.MAX_VALUE })
            val result = LinkedHashMap<Int?, List<Story>>(grouped)
            if (undated.isNotEmpty()) {
                result[null] = undated.map { it.toDomain() }
            }
            result
        }

    suspend fun searchKeyword(familyId: String, query: String): List<Story> =
        if (query.isBlank()) {
            emptyList()
        } else {
            db.storyDao().searchKeyword(familyId, query.trim()).map { it.toDomain() }
        }

    /**
     * Every story in the family, unfiltered. This is retrieval input for the librarian,
     * which applies the permission filter itself. Do not bind this to a screen.
     */
    suspend fun allForLibrarian(familyId: String): List<Story> =
        db.storyDao().all(familyId).map { it.toDomain() }

    suspend fun upsert(story: StoryEntity) = db.storyDao().upsert(story)

    fun observeTranscript(assetId: String) = db.transcriptDao().observeForAsset(assetId)

    suspend fun primaryAsset(storyId: String): AssetEntity? =
        db.assetDao().observeForStory(storyId).first().firstOrNull()

    suspend fun correctSegment(segmentId: Long, newText: String) =
        db.transcriptDao().correct(segmentId, newText)

    /**
     * AI-3, local edition. Runs the transcription service against a story's audio and
     * lands the segments in Room, flipping the story's status as it goes.
     *
     * PENDING -> RUNNING -> READY, or FAILED with the recording untouched. The recording
     * is never at risk from this path: transcription failing loses text, not voice.
     */
    suspend fun transcribeStory(storyId: String, transcription: TranscriptionService) {
        val story = db.storyDao().observeById(storyId).first() ?: return
        val asset = primaryAsset(storyId) ?: return

        db.storyDao().upsert(story.copy(transcriptStatus = TranscriptStatus.RUNNING))

        val result = transcription.transcribe(File(asset.localPath))
        result.fold(
            onSuccess = { t ->
                db.transcriptDao().clearForAsset(asset.assetId)
                db.transcriptDao().insertAll(
                    t.segments.map { seg ->
                        TranscriptSegmentEntity(
                            assetId = asset.assetId,
                            startMs = seg.startMs,
                            endMs = seg.endMs,
                            text = seg.text,
                            confidence = seg.confidence
                        )
                    }
                )
                db.storyDao().upsert(story.copy(transcriptStatus = TranscriptStatus.READY))
            },
            onFailure = {
                db.storyDao().upsert(story.copy(transcriptStatus = TranscriptStatus.FAILED))
            }
        )
    }

    /**
     * CAP-5. Turns a finished recording into a story the archive can hold.
     *
     * Everything is written locally and queued, never uploaded inline. The keeper is
     * standing in a kitchen with someone's grandmother and cannot wait on a network call,
     * and the recording must be safe the instant they tap save.
     */
    suspend fun saveRecording(
        familyId: String,
        createdByUserId: String,
        localAudioPath: String,
        durationMs: Long,
        title: String,
        narratorIds: List<String>,
        eraStart: Int?,
        eraEnd: Int?,
        eraPrecision: EraPrecision,
        placeLabel: String?,
        tags: List<String>,
        visibility: Visibility,
        aiUsePolicy: AiUsePolicy,
        area: ArchiveArea,
        now: Long
    ): String {
        val storyId = "s_" + UUID.randomUUID().toString().take(12)
        val assetId = "a_" + UUID.randomUUID().toString().take(12)

        val story = StoryEntity(
            storyId = storyId,
            familyId = familyId,
            title = title.ifBlank { "Untitled story" },
            kind = StoryKind.AUDIO,
            area = area,
            narratorIds = narratorIds,
            subjectPersonIds = narratorIds,
            eraStart = eraStart,
            eraEnd = eraEnd,
            eraPrecision = eraPrecision,
            placeLabel = placeLabel?.takeIf { it.isNotBlank() },
            tags = tags,
            visibility = visibility,
            aiUsePolicy = aiUsePolicy,
            // It is their actual voice. That is the whole point, and it is recorded here
            // rather than inferred later.
            provenance = Provenance.AUTHENTIC_RECORDING,
            durationMs = durationMs,
            assetCount = 1,
            transcriptStatus = TranscriptStatus.PENDING,
            uploadState = UploadState.LOCAL_ONLY,
            primaryAssetId = assetId,
            createdBy = createdByUserId,
            createdAt = now,
            updatedAt = now
        )

        val asset = AssetEntity(
            assetId = assetId,
            storyId = storyId,
            familyId = familyId,
            type = AssetType.AUDIO,
            localPath = localAudioPath,
            mimeType = "audio/mp4",
            bytes = runCatching { File(localAudioPath).length() }.getOrDefault(0L),
            durationMs = durationMs,
            uploadState = UploadState.LOCAL_ONLY,
            createdAt = now
        )

        db.storyDao().upsert(story)
        db.assetDao().upsert(asset)

        // TODO(DAT-2): the sync worker drains these. Until it exists they queue harmlessly.
        db.outboxDao().enqueue(
            OutboxEntity(
                op = OutboxOp.CREATE,
                collectionPath = "families/$familyId/stories",
                docId = storyId,
                payloadJson = "{\"storyId\":\"$storyId\"}",
                createdAt = now
            )
        )
        db.outboxDao().enqueue(
            OutboxEntity(
                op = OutboxOp.UPLOAD,
                collectionPath = "families/$familyId/assets",
                docId = assetId,
                payloadJson = "{\"assetId\":\"$assetId\"}",
                localFilePath = localAudioPath,
                createdAt = now
            )
        )

        return storyId
    }

    /**
     * Sprint 1 (OPS/UX): gives every teammate identical, non-empty data on first launch so
     * list rendering, timeline grouping, and search can be built before Firebase exists.
     * Delete this once DAT-2 lands real sync.
     */
    suspend fun seedDemoDataIfEmpty(familyId: String) {
        val dao = db.storyDao()
        if (dao.count(familyId) > 0) return

        db.personDao().upsertAll(
            listOf(
                PersonEntity(
                    personId = "p_ruth",
                    familyId = familyId,
                    displayName = "Ruth Delaney",
                    alsoKnownAs = listOf("Nana"),
                    birthYear = 1942,
                    birthPlace = "Vicksburg, MS",
                    relationLabel = "Grandmother",
                    consentGranted = true,
                    postMortemOk = true
                ),
                PersonEntity(
                    personId = "p_ray",
                    familyId = familyId,
                    displayName = "Ray Delaney",
                    birthYear = 1939,
                    deathYear = 2021,
                    relationLabel = "Grandfather",
                    consentGranted = true,
                    postMortemOk = true
                ),
                // Chosen family in the seed on purpose. Every screenshot, every first
                // run, every demo shows that Arv never asks how you are related.
                PersonEntity(
                    personId = "p_opal",
                    familyId = familyId,
                    displayName = "Miss Opal",
                    alsoKnownAs = listOf("Opal Hendricks"),
                    birthYear = 1946,
                    relationLabel = "Chosen family, next door since 1963",
                    consentGranted = true,
                    postMortemOk = true
                )
            )
        )
        db.relationshipDao().upsertAll(
            listOf(
                RelationshipEntity(
                    familyId = familyId,
                    fromPersonId = "p_opal",
                    toPersonId = "p_ruth",
                    kind = RelationshipKind.CHOSEN
                )
            )
        )

        dao.upsertAll(
            listOf(
                StoryEntity(
                    storyId = "s_levee",
                    familyId = familyId,
                    title = "The night the levee broke",
                    kind = StoryKind.AUDIO,
                    narratorIds = listOf("p_ruth"),
                    eraStart = 1953,
                    eraEnd = 1953,
                    eraPrecision = EraPrecision.EXACT,
                    placeLabel = "Vicksburg, MS",
                    tags = listOf("flood", "childhood"),
                    durationMs = 724_000,
                    assetCount = 1,
                    transcriptStatus = TranscriptStatus.READY,
                    uploadState = UploadState.SYNCED,
                    createdAt = 1_754_000_000_000
                ),
                StoryEntity(
                    storyId = "s_kitchen",
                    familyId = familyId,
                    title = "Sunday kitchen, Bellwood Ave",
                    kind = StoryKind.AUDIO,
                    narratorIds = listOf("p_ruth"),
                    eraStart = 1958,
                    eraEnd = 1964,
                    eraPrecision = EraPrecision.RANGE,
                    placeLabel = "Bellwood Ave, Chicago IL",
                    tags = listOf("food", "migration", "music"),
                    durationMs = 1_122_000,
                    assetCount = 7,
                    transcriptStatus = TranscriptStatus.READY,
                    uploadState = UploadState.SYNCED,
                    createdAt = 1_754_100_000_000
                ),
                StoryEntity(
                    storyId = "s_shipyard",
                    familyId = familyId,
                    title = "Uncle Ray on the shipyard years",
                    kind = StoryKind.AUDIO,
                    narratorIds = listOf("p_ray"),
                    eraStart = 1971,
                    eraEnd = 1979,
                    eraPrecision = EraPrecision.RANGE,
                    placeLabel = "Chicago, IL",
                    tags = listOf("work"),
                    durationMs = 1_672_000,
                    assetCount = 1,
                    transcriptStatus = TranscriptStatus.PENDING,
                    uploadState = UploadState.LOCAL_ONLY,
                    createdAt = 1_754_200_000_000
                ),
                StoryEntity(
                    storyId = "s_attic",
                    familyId = familyId,
                    title = "Box of photos from the attic",
                    kind = StoryKind.PHOTO_SET,
                    narratorIds = emptyList(),
                    eraPrecision = EraPrecision.UNKNOWN,
                    tags = listOf("unidentified"),
                    assetCount = 14,
                    transcriptStatus = TranscriptStatus.NONE,
                    uploadState = UploadState.LOCAL_ONLY,
                    createdBy = "u_dana",
                    createdAt = 1_754_300_000_000
                ),
                // Her voice belongs in the archive like anyone's. Family isn't blood here,
                // and the seed data is where a first-time user learns that.
                StoryEntity(
                    storyId = "s_opal_porch",
                    familyId = familyId,
                    title = "Miss Opal on forty years of porch coffee with Ruth",
                    kind = StoryKind.AUDIO,
                    narratorIds = listOf("p_opal"),
                    subjectPersonIds = listOf("p_opal"),
                    eraStart = 1963,
                    eraEnd = 2003,
                    eraPrecision = EraPrecision.RANGE,
                    placeLabel = "Bellwood Ave, Chicago IL",
                    tags = listOf("neighbors", "friendship"),
                    durationMs = 498_000,
                    assetCount = 1,
                    transcriptStatus = TranscriptStatus.READY,
                    uploadState = UploadState.SYNCED,
                    createdBy = "u_dana",
                    createdAt = 1_754_350_000_000
                ),
                // An ordinary Tuesday. Same object type as a recorded story, on purpose:
                // in thirty years this is the only record that this day happened.
                StoryEntity(
                    familyId = familyId,
                    storyId = "s_tooth",
                    title = "Maya lost her first tooth. Extremely dramatic, she says.",
                    kind = StoryKind.UPDATE,
                    narratorIds = emptyList(),
                    eraStart = 2026,
                    eraEnd = 2026,
                    eraPrecision = EraPrecision.EXACT,
                    tags = listOf("maya", "milestone"),
                    provenance = Provenance.HUMAN_WRITTEN,
                    assetCount = 1,
                    transcriptStatus = TranscriptStatus.NONE,
                    uploadState = UploadState.SYNCED,
                    createdBy = "u_theo",
                    createdAt = 1_754_400_000_000
                ),
                // Demo step 7. Owned by someone else and marked private, so it must never
                // surface in the feed, in search, or in either librarian scope. It should
                // appear only as part of a withheld count.
                StoryEntity(
                    storyId = "s_private",
                    familyId = familyId,
                    title = "Sunday mornings I did not go to church",
                    kind = StoryKind.AUDIO,
                    narratorIds = listOf("p_ray"),
                    eraStart = 1974,
                    eraEnd = 1974,
                    eraPrecision = EraPrecision.EXACT,
                    tags = listOf("faith", "sunday"),
                    visibility = Visibility.PRIVATE,
                    aiUsePolicy = AiUsePolicy.NONE,
                    durationMs = 402_000,
                    assetCount = 1,
                    transcriptStatus = TranscriptStatus.READY,
                    uploadState = UploadState.SYNCED,
                    createdBy = "u_theo",
                    createdAt = 1_754_500_000_000
                )
            )
        )
    }
}
