package com.arv.app.core.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import com.arv.app.core.model.Confidence
import com.arv.app.core.model.AiUsePolicy
import com.arv.app.core.model.ArchiveArea
import com.arv.app.core.model.AssetType
import com.arv.app.core.model.EraPrecision
import com.arv.app.core.model.OutboxOp
import com.arv.app.core.model.Person
import com.arv.app.core.model.ProfileState
import com.arv.app.core.model.Provenance
import com.arv.app.core.model.Relationship
import com.arv.app.core.model.RelationshipKind
import com.arv.app.core.model.Story
import com.arv.app.core.model.StoryKind
import com.arv.app.core.model.TranscriptSegment
import com.arv.app.core.model.TranscriptStatus
import com.arv.app.core.model.UploadState
import com.arv.app.core.model.Visibility

@Entity(tableName = "people", indices = [Index("familyId")])
data class PersonEntity(
    @PrimaryKey val personId: String,
    val familyId: String,
    val displayName: String,
    val alsoKnownAs: List<String> = emptyList(),
    val birthYear: Int? = null,
    val deathYear: Int? = null,
    /**
     * The later end of a death nobody can date exactly.
     *
     * "2021 or 2022" is how a family actually remembers a death, and picking one of the two
     * would turn somebody's honest uncertainty into a fact the archive appears to vouch
     * for. Null means the year in [deathYear] is the whole answer.
     */
    val deathYearEnd: Int? = null,
    val birthPlace: String? = null,
    val relationLabel: String? = null,
    val linkedUserId: String? = null,
    val state: ProfileState = ProfileState.LIVING,
    val memoryStewardUserId: String? = null,
    val consentGranted: Boolean = false,
    val postMortemOk: Boolean = false,
    /**
     * How well established this person is. Defaults to FAMILY_TOLD because somebody in the
     * family typed them in, which is a real claim and should not masquerade as a document.
     */
    val confidence: Confidence = Confidence.FAMILY_TOLD,
    /** Where the claim came from: an obituary, a census page, a relative's name. */
    val source: String? = null,
    /** Set when a person has been checked against a record, so the work is not redone. */
    val verifiedAt: Long? = null,
    /**
     * What the record says in the words of whoever wrote it down.
     *
     * The importer read these and dropped them on the floor, so every caveat a compiled
     * history carried, every "predeceased his parents" and every disputed date, was lost on
     * the way in while the confident parts survived.
     */
    val note: String? = null,
    val updatedAt: Long = 0L
)

/**
 * One edge of the family tree, stored once and rendered from both ends.
 *
 * Uncertain links are marked rather than deleted. Families genuinely disagree about who
 * someone's father was, and an archive that quietly picks a side is editorializing.
 */
@Entity(
    tableName = "relationships",
    primaryKeys = ["fromPersonId", "toPersonId", "kind"],
    indices = [Index("familyId"), Index("fromPersonId"), Index("toPersonId")]
)
data class RelationshipEntity(
    val familyId: String,
    val fromPersonId: String,
    val toPersonId: String,
    val kind: RelationshipKind,
    val uncertain: Boolean = false,
    val updatedAt: Long = 0L
)

@Entity(tableName = "stories", indices = [Index("familyId"), Index("eraStart")])
data class StoryEntity(
    @PrimaryKey val storyId: String,
    val familyId: String,
    val title: String,
    val kind: StoryKind,
    val area: ArchiveArea = ArchiveArea.STORIES,
    val narratorIds: List<String> = emptyList(),
    val subjectPersonIds: List<String> = emptyList(),
    val eraStart: Int? = null,
    val eraEnd: Int? = null,
    val eraPrecision: EraPrecision = EraPrecision.UNKNOWN,
    val placeLabel: String? = null,
    val tags: List<String> = emptyList(),
    val visibility: Visibility = Visibility.FAMILY,
    val aiUsePolicy: AiUsePolicy = AiUsePolicy.SUMMARY_OK,
    val provenance: Provenance = Provenance.AUTHENTIC_RECORDING,
    val sharedWithUserIds: List<String> = emptyList(),
    val restricted: Boolean = false,
    /**
     * For [com.arv.app.core.model.Visibility.BRANCH]: the ancestor whose line this belongs
     * to. Readable by anyone descended from that person.
     *
     * A branch is named by an ancestor rather than stored as a group, because a family is a
     * web of trees and not one tree. Every person is the centre of their own view and a
     * node in everyone else's, so nobody sits in exactly one branch: you are in your
     * father's line and your mother's line and your grandmother's at the same time. Naming
     * the ancestor and walking up from the reader is the only version of "my branch" that
     * survives that.
     */
    val branchRootPersonId: String? = null,
    val durationMs: Long = 0L,
    val assetCount: Int = 0,
    val transcriptStatus: TranscriptStatus = TranscriptStatus.NONE,
    val uploadState: UploadState = UploadState.LOCAL_ONLY,
    val primaryAssetId: String? = null,
    val createdBy: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)

@Entity(tableName = "assets", indices = [Index("storyId"), Index("uploadState")])
data class AssetEntity(
    @PrimaryKey val assetId: String,
    val storyId: String,
    val familyId: String,
    val type: AssetType,
    /** Always set. The phone is the source of truth until the upload is confirmed. */
    val localPath: String,
    val remotePath: String? = null,
    val mimeType: String,
    val bytes: Long = 0L,
    val durationMs: Long? = null,
    val sha256: String? = null,
    val ocrText: String? = null,
    val uploadState: UploadState = UploadState.LOCAL_ONLY,
    val createdAt: Long = 0L
)

@Entity(tableName = "transcript_segments", indices = [Index("assetId")])
data class TranscriptSegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val assetId: String,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val confidence: Float = 0f,
    val humanVerified: Boolean = false,
    /** Kept when a human edits the line, so we never lose what the machine actually heard. */
    val originalText: String? = null
)

/**
 * Every write in the app goes here first. A WorkManager job drains it.
 * This table is the entire offline story. See docs/SPEC.md §3.
 */
@Entity(tableName = "outbox", indices = [Index("createdAt")])
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val op: OutboxOp,
    val collectionPath: String,
    val docId: String,
    val payloadJson: String,
    val localFilePath: String? = null,
    val attempts: Int = 0,
    val lastError: String? = null,
    val createdAt: Long = 0L
)

/**
 * ASCII unit separator, not a comma. Names, tags, and place labels legitimately contain
 * commas ("Bellwood Ave, Chicago IL") and splitting on one would corrupt them.
 */
private const val SEP = "\u001F"

class Converters {
    @TypeConverter fun listToString(value: List<String>?): String = value?.joinToString(SEP) ?: ""
    @TypeConverter fun stringToList(value: String?): List<String> =
        if (value.isNullOrEmpty()) emptyList() else value.split(SEP)

    @TypeConverter fun confidenceToString(v: Confidence): String = v.name
    @TypeConverter fun stringToConfidence(v: String): Confidence = Confidence.valueOf(v)

    @TypeConverter fun storyKindToString(v: StoryKind): String = v.name
    @TypeConverter fun stringToStoryKind(v: String): StoryKind = StoryKind.valueOf(v)

    @TypeConverter fun assetTypeToString(v: AssetType): String = v.name
    @TypeConverter fun stringToAssetType(v: String): AssetType = AssetType.valueOf(v)

    @TypeConverter fun eraPrecisionToString(v: EraPrecision): String = v.name
    @TypeConverter fun stringToEraPrecision(v: String): EraPrecision = EraPrecision.valueOf(v)

    @TypeConverter fun visibilityToString(v: Visibility): String = v.name
    @TypeConverter fun stringToVisibility(v: String): Visibility = Visibility.valueOf(v)

    @TypeConverter fun uploadStateToString(v: UploadState): String = v.name
    @TypeConverter fun stringToUploadState(v: String): UploadState = UploadState.valueOf(v)

    @TypeConverter fun transcriptStatusToString(v: TranscriptStatus): String = v.name
    @TypeConverter fun stringToTranscriptStatus(v: String): TranscriptStatus = TranscriptStatus.valueOf(v)

    @TypeConverter fun outboxOpToString(v: OutboxOp): String = v.name
    @TypeConverter fun stringToOutboxOp(v: String): OutboxOp = OutboxOp.valueOf(v)

    @TypeConverter fun aiUsePolicyToString(v: AiUsePolicy): String = v.name
    @TypeConverter fun stringToAiUsePolicy(v: String): AiUsePolicy = AiUsePolicy.valueOf(v)

    @TypeConverter fun provenanceToString(v: Provenance): String = v.name
    @TypeConverter fun stringToProvenance(v: String): Provenance = Provenance.valueOf(v)

    @TypeConverter fun profileStateToString(v: ProfileState): String = v.name
    @TypeConverter fun stringToProfileState(v: String): ProfileState = ProfileState.valueOf(v)

    @TypeConverter fun archiveAreaToString(v: ArchiveArea): String = v.name
    @TypeConverter fun stringToArchiveArea(v: String): ArchiveArea = ArchiveArea.valueOf(v)

    @TypeConverter fun relationshipKindToString(v: RelationshipKind): String = v.name
    @TypeConverter fun stringToRelationshipKind(v: String): RelationshipKind =
        RelationshipKind.valueOf(v)
}

// --- mapping to domain ---

fun StoryEntity.toDomain() = Story(
    storyId = storyId,
    // Every field crosses, and this one is load-bearing: canRead checks the family
    // boundary first and fails closed, so a Story that lost its familyId here was
    // rejected by every screen and the whole archive rendered empty.
    familyId = familyId,
    title = title,
    kind = kind,
    area = area,
    narratorIds = narratorIds,
    subjectPersonIds = subjectPersonIds,
    eraStart = eraStart,
    eraEnd = eraEnd,
    eraPrecision = eraPrecision,
    placeLabel = placeLabel,
    tags = tags,
    visibility = visibility,
    aiUsePolicy = aiUsePolicy,
    provenance = provenance,
    sharedWithUserIds = sharedWithUserIds,
    restricted = restricted,
    branchRootPersonId = branchRootPersonId,
    durationMs = durationMs,
    assetCount = assetCount,
    transcriptStatus = transcriptStatus,
    uploadState = uploadState,
    createdBy = createdBy,
    createdAt = createdAt
)

fun PersonEntity.toDomain() = Person(
    personId = personId,
    displayName = displayName,
    alsoKnownAs = alsoKnownAs,
    birthYear = birthYear,
    deathYear = deathYear,
    birthPlace = birthPlace,
    relationLabel = relationLabel,
    linkedUserId = linkedUserId,
    state = state,
    memoryStewardUserId = memoryStewardUserId,
    consentGranted = consentGranted,
    postMortemOk = postMortemOk,
    confidence = confidence,
    source = source,
    verifiedAt = verifiedAt,
    deathYearEnd = deathYearEnd,
    note = note
)

fun RelationshipEntity.toDomain() = Relationship(
    fromPersonId = fromPersonId,
    toPersonId = toPersonId,
    kind = kind,
    uncertain = uncertain
)

fun TranscriptSegmentEntity.toDomain() = TranscriptSegment(
    id = id,
    assetId = assetId,
    startMs = startMs,
    endMs = endMs,
    text = text,
    confidence = confidence,
    humanVerified = humanVerified
)
