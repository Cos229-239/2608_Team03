package com.arv.app.core.data

import com.arv.app.core.ai.Lineage
import com.arv.app.core.ai.TranscriptionService
import com.arv.app.core.data.local.ArvDatabase
import com.arv.app.core.data.local.AssetEntity
import com.arv.app.core.data.local.OutboxEntity
import com.arv.app.core.data.local.PersonEntity
import com.arv.app.core.data.local.RelationshipEntity
import com.arv.app.core.data.local.StoryEntity
import com.arv.app.core.data.local.TranscriptSegmentEntity
import com.arv.app.core.model.RelationshipKind
import com.arv.app.core.session.ActiveSession
import com.arv.app.core.data.local.toDomain
import com.arv.app.core.model.ArchiveArea
import com.arv.app.core.model.Confidence
import com.arv.app.core.model.AssetType
import com.arv.app.core.model.OutboxOp
import java.io.File
import java.util.UUID
import com.arv.app.core.model.AiUsePolicy
import com.arv.app.core.model.EraPrecision
import com.arv.app.core.model.Person
import com.arv.app.core.model.ProfileState
import com.arv.app.core.model.Provenance
import com.arv.app.core.model.Story
import com.arv.app.core.model.StoryKind
import com.arv.app.core.model.TranscriptStatus
import com.arv.app.core.model.UploadState
import com.arv.app.core.model.Visibility
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
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

    fun observeDocuments(familyId: String): Flow<List<Story>> =
        db.storyDao().observeDocuments(familyId).map { rows -> rows.map { it.toDomain() } }

    /**
     * Recomputes who the signed-in user is and who they descend from, and caches it on
     * the session.
     *
     * BRANCH visibility reads that cache, so this has to run before any screen filters,
     * and again whenever the tree changes. It fails to an empty set rather than throwing:
     * an archive whose owner is not yet linked to a person should show nothing
     * branch-scoped, not everything.
     */
    suspend fun refreshLineage(familyId: String, userId: String) {
        val people = db.personDao().all(familyId)
        val me = people.firstOrNull { it.linkedUserId == userId }

        val steward = people.filter { it.memoryStewardUserId == userId }.map { it.personId }
        ActiveSession.setPersonIds((listOfNotNull(me?.personId) + steward).toSet())

        if (me == null) {
            ActiveSession.setLineage(emptySet())
            return
        }
        val edges = db.relationshipDao().observeAll(familyId).first().map { it.toDomain() }
        ActiveSession.setLineage(Lineage.ancestorsOf(me.personId, edges))
    }

    /**
     * The ancestors this user could name a branch after, nearest generation first.
     *
     * Empty for someone with no recorded parents, which is the honest answer: you cannot
     * scope a memory to a line the archive does not know about yet. The recorder hides the
     * option rather than offering a choice that would make a recording unreadable.
     */
    suspend fun branchChoicesFor(familyId: String, userId: String): List<Person> {
        val people = db.personDao().all(familyId)
        val me = people.firstOrNull { it.linkedUserId == userId } ?: return emptyList()
        val edges = db.relationshipDao().observeAll(familyId).first().map { it.toDomain() }
        val ids = Lineage.branchChoicesFor(me.personId, edges).toSet()
        return people.filter { it.personId in ids }.map { it.toDomain() }
    }

    /**
     * Writes a parsed family history into the archive.
     *
     * Idempotent by name: importing the same file twice updates people rather than
     * creating a second copy of everyone, because the realistic use is importing, fixing
     * something in the source, and importing again.
     *
     * Links are attached to the importing user's own person. That is the only viewpoint the
     * file describes, since every label in it was written relative to whoever compiled it.
     */
    suspend fun importFamily(
        familyId: String,
        userId: String,
        parsed: FamilyImport.Parsed,
        nowMillis: Long
    ): Int {
        val existing = db.personDao().all(familyId)
        val me = existing.firstOrNull { it.linkedUserId == userId }
        val byName = existing.associateBy { it.displayName.trim().lowercase() }
        var written = 0

        for (person in parsed.people) {
            val key = person.displayName.trim().lowercase()
            // Never overwrite the importer's own row with a row about themselves.
            if (me != null && key == me.displayName.trim().lowercase()) continue

            val id = byName[key]?.personId ?: ("p_" + UUID.randomUUID().toString().take(8))
            db.personDao().upsert(
                PersonEntity(
                    personId = id,
                    familyId = familyId,
                    displayName = person.displayName,
                    alsoKnownAs = person.alsoKnownAs,
                    birthYear = person.birthYear,
                    deathYear = person.deathYear,
                    deathYearEnd = person.deathYearEnd,
                    note = person.note,
                    birthPlace = person.birthPlace,
                    relationLabel = person.relationLabel,
                    // A stated death counts as much as a dated one. Requiring a year meant
                    // somebody known to have died, with no year anybody recorded, imported
                    // as living.
                    state = if (person.deceased) ProfileState.MEMORIAL
                    else ProfileState.LIVING,
                    confidence = person.confidence,
                    source = person.source,
                    updatedAt = nowMillis
                )
            )
            written++

            person.spouseName?.let { spouseName ->
                byName[spouseName.trim().lowercase()]?.personId?.let { spouseId ->
                    db.relationshipDao().upsert(
                        RelationshipEntity(
                            familyId = familyId,
                            fromPersonId = id,
                            toPersonId = spouseId,
                            kind = RelationshipKind.SPOUSE,
                            updatedAt = nowMillis
                        )
                    )
                }
            }

            // Named parents win over a relation label. A label describes the writer's
            // relationship to someone; parents describe the person themselves, and only
            // parents can tell a half sibling from a step sibling.
            for (parentName in person.parentNames) {
                val parentId = byName[parentName.trim().lowercase()]?.personId
                    ?: me?.takeIf { it.displayName.trim().lowercase() == parentName.trim().lowercase() }?.personId
                if (parentId != null) {
                    db.relationshipDao().upsert(
                        RelationshipEntity(
                            familyId = familyId,
                            fromPersonId = parentId,
                            toPersonId = id,
                            kind = RelationshipKind.PARENT,
                            updatedAt = nowMillis
                        )
                    )
                }
            }

            // Both, not either. "parents" says who this person's parents are;
            // relationLabel says how they stand to whoever compiled the file. Making them
            // exclusive meant that naming someone's parents silently cut their own link to
            // the importer, so naming someone's mother deleted their own link to the
            // importer and every tree below them emptied out.
            val kind = person.linkToImporter
            if (kind != null && me != null) {
                db.relationshipDao().upsert(
                    RelationshipEntity(
                        familyId = familyId,
                        fromPersonId = id,
                        toPersonId = me.personId,
                        kind = kind,
                        // Everything the file was not certain about stays marked uncertain,
                        // so a disputed link is visible rather than quietly authoritative.
                        uncertain = person.confidence == Confidence.UNVERIFIED ||
                            person.confidence == Confidence.CONFLICTED,
                        updatedAt = nowMillis
                    )
                )
            }
        }

        // The graph just changed, so the viewer's own lineage is stale.
        refreshLineage(familyId, userId)
        return written
    }

    /**
     * People the archive holds but cannot place: no chain of parents connects them to the
     * person using the app, so which side of the family they are on is genuinely unknown.
     *
     * This is the worklist. An imported history arrives full of these, because a label like
     * "3x great-grandmother" says how far up somebody sits and never says through whom, and
     * the app refuses to guess. Every one of them is a question with an answer somebody
     * alive probably still has.
     */
    fun observeUnplaced(familyId: String, userId: String): Flow<List<Person>> =
        combine(
            db.personDao().observeAll(familyId),
            db.relationshipDao().observeAll(familyId)
        ) { people, edges ->
            val me = people.firstOrNull { it.linkedUserId == userId }
                ?: return@combine emptyList()
            val rels = edges.map { it.toDomain() }
            people
                .filter { it.personId != me.personId }
                .filter { Lineage.isUnplaced(it.personId, me.personId, rels) }
                .map { it.toDomain() }
        }

    /**
     * Records that one person is another's parent, and recomputes what that changed.
     *
     * Placing a single person can place a whole line behind them, which is the point: name
     * one link and everyone standing behind it falls into place.
     */
    suspend fun connectParent(
        familyId: String,
        parentPersonId: String,
        childPersonId: String,
        userId: String,
        nowMillis: Long
    ) {
        if (parentPersonId == childPersonId) return
        db.relationshipDao().upsert(
            RelationshipEntity(
                familyId = familyId,
                fromPersonId = parentPersonId,
                toPersonId = childPersonId,
                kind = RelationshipKind.PARENT,
                updatedAt = nowMillis
            )
        )
        refreshLineage(familyId, userId)
    }

    /** People the archive is holding on somebody's word alone, for the verify list. */
    fun observeNeedingVerification(familyId: String): Flow<List<Person>> =
        db.personDao().observeAll(familyId).map { rows ->
            rows.filter {
                it.confidence == Confidence.UNVERIFIED || it.confidence == Confidence.CONFLICTED
            }.map { it.toDomain() }
        }

    /** Records that somebody checked a person against a real source. */
    suspend fun markVerified(personId: String, source: String, nowMillis: Long) {
        val p = db.personDao().byId(personId) ?: return
        db.personDao().upsert(
            p.copy(
                confidence = Confidence.DOCUMENTED,
                source = source.ifBlank { p.source },
                verifiedAt = nowMillis,
                updatedAt = nowMillis
            )
        )
    }

    fun observeRelationships(familyId: String) =
        db.relationshipDao().observeAll(familyId).map { rows -> rows.map { it.toDomain() } }

    /** One-shot reads for the librarian pipeline. */
    suspend fun peopleFor(familyId: String) =
        db.personDao().all(familyId).map { it.toDomain() }

    suspend fun transcriptForStory(storyId: String) =
        db.transcriptDao().forStory(storyId).map { it.toDomain() }

    fun observePendingSyncCount(): Flow<Int> = db.outboxDao().observePendingCount()

    /** Total recorded audio for one person. Drives the hours-preserved meter. */
    fun observeRecordedMsFor(familyId: String, personId: String): Flow<Long> =
        db.personDao().observeRecordedMsFor(familyId, personId)

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

    // --- Making an archive real (DAT-1 groundwork) ---

    /**
     * Creates a family and the person who owns it.
     *
     * The owner is written as a [PersonEntity] as well as a user id, because in this app
     * the person keeping the archive is also in it. Their consent is set at creation:
     * they are the one choosing to record, and asking someone to consent to their own
     * archive would be theater.
     */
    suspend fun createFamily(
        familyName: String,
        ownerDisplayName: String,
        nowMillis: Long
    ): NewFamily {
        val familyId = "fam_" + UUID.randomUUID().toString().take(8)
        val userId = "u_" + UUID.randomUUID().toString().take(8)
        val personId = "p_" + UUID.randomUUID().toString().take(8)

        db.personDao().upsert(
            PersonEntity(
                personId = personId,
                familyId = familyId,
                displayName = ownerDisplayName.trim(),
                relationLabel = "You",
                linkedUserId = userId,
                state = ProfileState.LIVING,
                consentGranted = true,
                updatedAt = nowMillis
            )
        )

        return NewFamily(familyId, userId, personId, familyName.trim())
    }

    /**
     * Adds someone to the archive.
     *
     * Consent stays false and is never inferred from a relative having typed the name in.
     * The people list renders that gap in red on purpose; a missing consent record is
     * information, not an error to hide.
     *
     * A death year is what moves a profile to MEMORIAL. Nothing else does, because that
     * transition changes who may add to the profile and it should never happen by accident.
     */
    suspend fun addPerson(
        familyId: String,
        displayName: String,
        relationLabel: String? = null,
        birthYear: Int? = null,
        deathYear: Int? = null,
        birthPlace: String? = null,
        nowMillis: Long
    ): String {
        val personId = "p_" + UUID.randomUUID().toString().take(8)
        db.personDao().upsert(
            PersonEntity(
                personId = personId,
                familyId = familyId,
                displayName = displayName.trim(),
                relationLabel = relationLabel?.trim()?.takeIf { it.isNotEmpty() },
                birthYear = birthYear,
                deathYear = deathYear,
                birthPlace = birthPlace?.trim()?.takeIf { it.isNotEmpty() },
                state = if (deathYear != null) ProfileState.MEMORIAL else ProfileState.LIVING,
                updatedAt = nowMillis
            )
        )
        return personId
    }

    fun observeTranscript(assetId: String) = db.transcriptDao().observeForAsset(assetId)

    suspend fun primaryAsset(storyId: String): AssetEntity? =
        db.assetDao().observeForStory(storyId).first().firstOrNull()

    /**
     * storyId to the audio file behind it, for every story in the family that actually has
     * one on disk. List screens use this to decide whether a play button is real.
     *
     * Seed rows carry "seed://" paths and no file, so they are dropped here rather than in
     * the UI: a play button that cannot play should not be offered in the first place. The
     * existence check touches the disk, which is why this runs off the main thread.
     */
    fun observeAudioPaths(familyId: String): Flow<Map<String, String>> =
        db.assetDao().observeForFamily(familyId)
            .map { rows ->
                rows.filter {
                    it.type == AssetType.AUDIO &&
                        !it.localPath.startsWith("seed://") &&
                        File(it.localPath).exists()
                }
                    // Oldest first, matching primaryAsset, so the feed and the story screen
                    // never disagree about which recording a story means.
                    .groupBy { it.storyId }
                    .mapValues { (_, assets) -> assets.first().localPath }
            }
            .flowOn(Dispatchers.IO)

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
        branchRootPersonId: String? = null,
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
            // Only meaningful for BRANCH, and deliberately dropped otherwise so a story
            // cannot carry a stale line from a visibility the person changed their mind about.
            branchRootPersonId = branchRootPersonId.takeIf { visibility == Visibility.BRANCH },
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
     * Files a photograph or a record into the archive.
     *
     * The other half of what a family actually has. Not every inheritance is a voice: it is
     * also the marriage certificate, the ship postcard, the photograph with four people on
     * the back of it in pencil. Those arrive already made, so unlike a recording there is
     * nothing to transcribe and nothing to wait for.
     *
     * [localPath] must already be inside app storage. The caller copies the picked file in
     * first, because a content:// URI is a temporary grant that dies with the picker, and
     * an archive that stores a borrowed pointer is an archive that empties itself the next
     * time someone tidies their gallery.
     */
    suspend fun saveDocument(
        familyId: String,
        createdByUserId: String,
        localPath: String,
        mimeType: String,
        title: String,
        subjectPersonIds: List<String>,
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
        val storyId = "d_" + UUID.randomUUID().toString().take(12)
        val assetId = "a_" + UUID.randomUUID().toString().take(12)
        val isImage = mimeType.startsWith("image/")

        val story = StoryEntity(
            storyId = storyId,
            familyId = familyId,
            title = title.ifBlank { if (isImage) "Untitled photograph" else "Untitled record" },
            kind = if (isImage) StoryKind.PHOTO_SET else StoryKind.DOCUMENT,
            area = area,
            narratorIds = emptyList(),
            subjectPersonIds = subjectPersonIds,
            eraStart = eraStart,
            eraEnd = eraEnd,
            eraPrecision = eraPrecision,
            placeLabel = placeLabel?.takeIf { it.isNotBlank() },
            tags = tags,
            visibility = visibility,
            aiUsePolicy = aiUsePolicy,
            // Their handwriting, their photograph, their document. Not a recording, and
            // never labelled as one.
            provenance = Provenance.AUTHENTIC_DOCUMENT,
            durationMs = 0L,
            assetCount = 1,
            // There is no audio here, so there is nothing pending. Marking it PENDING
            // would leave every document sitting under "Transcribing" forever.
            transcriptStatus = TranscriptStatus.NONE,
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
            type = if (isImage) AssetType.IMAGE else AssetType.DOCUMENT,
            localPath = localPath,
            mimeType = mimeType,
            bytes = runCatching { File(localPath).length() }.getOrDefault(0L),
            uploadState = UploadState.LOCAL_ONLY,
            createdAt = now
        )

        db.storyDao().upsert(story)
        db.assetDao().upsert(asset)

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
                localFilePath = localPath,
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
                ),

                // --- Documents ---
                // Records rather than recordings. They belong to LINEAGE because a
                // marriage certificate is a fact about the family structure, not a story
                // somebody told, and the tree reads from them.
                StoryEntity(
                    storyId = "d_marriage_ruth_ray",
                    familyId = familyId,
                    title = "Marriage certificate, Ruth and Ray",
                    kind = StoryKind.DOCUMENT,
                    area = ArchiveArea.LINEAGE,
                    subjectPersonIds = listOf("p_ruth", "p_ray"),
                    eraStart = 1961,
                    eraEnd = 1961,
                    eraPrecision = EraPrecision.EXACT,
                    placeLabel = "Cook County, IL",
                    tags = listOf("marriage", "record"),
                    provenance = Provenance.AUTHENTIC_DOCUMENT,
                    assetCount = 1,
                    uploadState = UploadState.SYNCED,
                    createdAt = 1_754_600_000_000
                ),
                StoryEntity(
                    storyId = "d_death_ray",
                    familyId = familyId,
                    title = "Death record, Ray Delaney",
                    kind = StoryKind.DOCUMENT,
                    area = ArchiveArea.LINEAGE,
                    subjectPersonIds = listOf("p_ray"),
                    eraStart = 2021,
                    eraEnd = 2021,
                    eraPrecision = EraPrecision.EXACT,
                    placeLabel = "Cook County, IL",
                    tags = listOf("death", "record"),
                    provenance = Provenance.AUTHENTIC_DOCUMENT,
                    assetCount = 1,
                    uploadState = UploadState.SYNCED,
                    createdAt = 1_754_610_000_000
                ),
                // Nobody has found this one. It is in a box in somebody's garage and the
                // family knows it exists. Recording the gap is what eventually gets the
                // box opened, so a wanted document is a real record with no file yet.
                StoryEntity(
                    storyId = "d_ship_postcard",
                    familyId = familyId,
                    title = "Postcard of the ship Ruth's mother came over on",
                    kind = StoryKind.DOCUMENT,
                    area = ArchiveArea.LINEAGE,
                    subjectPersonIds = listOf("p_ruth"),
                    eraPrecision = EraPrecision.UNKNOWN,
                    tags = listOf("migration", "wanted"),
                    provenance = Provenance.AUTHENTIC_DOCUMENT,
                    assetCount = 0,
                    uploadState = UploadState.LOCAL_ONLY,
                    createdAt = 1_754_620_000_000
                )
            )
        )

        // Transcript lines for the seeded recordings, so the librarian has actual words
        // to search and quote on a first run. Without these, "READY" is a costume.
        db.assetDao().upsert(
            AssetEntity(
                assetId = "a_levee",
                storyId = "s_levee",
                familyId = familyId,
                type = AssetType.AUDIO,
                localPath = "seed://s_levee",
                mimeType = "audio/m4a",
                durationMs = 724_000,
                uploadState = UploadState.SYNCED,
                createdAt = 1_754_000_000_000
            )
        )
        db.assetDao().upsert(
            AssetEntity(
                assetId = "a_kitchen",
                storyId = "s_kitchen",
                familyId = familyId,
                type = AssetType.AUDIO,
                localPath = "seed://s_kitchen",
                mimeType = "audio/m4a",
                durationMs = 1_122_000,
                uploadState = UploadState.SYNCED,
                createdAt = 1_754_100_000_000
            )
        )
        db.assetDao().upsert(
            AssetEntity(
                assetId = "a_opal_porch",
                storyId = "s_opal_porch",
                familyId = familyId,
                type = AssetType.AUDIO,
                localPath = "seed://s_opal_porch",
                mimeType = "audio/m4a",
                durationMs = 498_000,
                uploadState = UploadState.SYNCED,
                createdAt = 1_754_350_000_000
            )
        )
        db.transcriptDao().insertAll(
            listOf(
                TranscriptSegmentEntity(
                    assetId = "a_levee", startMs = 12_000, endMs = 31_000,
                    text = "The water came up Jackson Street before sunrise and Daddy carried us out one at a time.",
                    confidence = 0.94f
                ),
                TranscriptSegmentEntity(
                    assetId = "a_levee", startMs = 31_000, endMs = 52_000,
                    text = "We watched the levee go from the church roof. The whole town was on that roof by noon.",
                    confidence = 0.91f
                ),
                TranscriptSegmentEntity(
                    assetId = "a_kitchen", startMs = 8_000, endMs = 24_000,
                    text = "Sunday mornings the whole house smelled like biscuits and coffee before anybody was even dressed.",
                    confidence = 0.95f
                ),
                TranscriptSegmentEntity(
                    assetId = "a_kitchen", startMs = 24_000, endMs = 47_000,
                    text = "Mama kept the radio on the gospel station and you learned to roll dough to that music.",
                    confidence = 0.9f
                ),
                TranscriptSegmentEntity(
                    assetId = "a_opal_porch", startMs = 5_000, endMs = 22_000,
                    text = "Forty years of coffee on that porch with Ruth. We solved every problem this street ever had.",
                    confidence = 0.93f
                )
            )
        )
    }
}

/** What [StoryRepository.createFamily] hands back so the session can be opened on it. */
data class NewFamily(
    val familyId: String,
    val userId: String,
    val ownerPersonId: String,
    val familyName: String
)
