package com.arv.app.core.model

/** Role inside a family. Enforced again in Firestore rules. The client copy is for UI only. */
enum class MemberRole { OWNER, KEEPER, CONTRIBUTOR, VIEWER }

enum class StoryKind { AUDIO, PHOTO_SET, DOCUMENT, COLLECTION, UPDATE }

/**
 * The four archives a family carries forward. Every memory belongs to exactly one.
 *
 * This is not a tag. It changes who controls the record and what a librarian may do with
 * it, which is why it is an enum on the object rather than a string in a list.
 */
enum class ArchiveArea {
    /** Relationships, names, dates, migrations, places, branches. */
    LINEAGE,

    /** Language, recipes, songs, traditions, faith, celebrations, community. */
    CULTURE,

    /** Voices, photographs, documents, memories, lived experience. */
    STORIES,

    /**
     * Recurring conditions, causes of death, allergies, and other health information a
     * relative deliberately contributed.
     *
     * Health is structurally different from the other three. A record like "her mother
     * had early-onset dementia" is simultaneously one person's private medical history
     * and another person's risk information, which is exactly why families lose it. So
     * control follows the person the record is ABOUT, not the relative who typed it in,
     * and a librarian may never reason across these records. See [MemoryAccess] and
     * [com.arv.app.core.ai.ClinicalClaimGuard].
     */
    HEALTH
}

enum class AssetType { AUDIO, IMAGE, DOCUMENT }

/**
 * How much we actually know about when something happened.
 *
 * UNKNOWN is a legitimate, common answer. Forcing a precise date is how archives end up
 * holding confident lies, so the model carries the uncertainty instead of hiding it.
 */
enum class EraPrecision { EXACT, RANGE, UNKNOWN }

/**
 * Who can see a memory. Set by whoever created it, never widened by anyone else.
 *
 * SELECTED and BRANCH exist because a family is not one flat audience. A story you would
 * tell your sister is not always a story you would tell forty relatives at a reunion.
 */
enum class Visibility { PRIVATE, SELECTED, BRANCH, FAMILY }

/**
 * What a librarian is allowed to do with a memory. Separate from [Visibility] on purpose:
 * "my cousins may read this" and "a model may summarize this" are different permissions,
 * and conflating them is how people get surprised by their own archive.
 */
enum class AiUsePolicy {
    /** Never surfaced by a librarian. Retrievable only by opening it directly. */
    NONE,

    /** May be found and cited, but only quoted verbatim. No paraphrase. */
    QUOTE_ONLY,

    /** May be summarized and woven into an answer. */
    SUMMARY_OK
}

/**
 * Where the content came from. This is the line the product must never blur.
 *
 * Restoring and organizing an authentic recording is preservation. Generating words a
 * person never said and presenting them as that person is fabrication, and in a tool built
 * for grief it is the most harmful thing the software could do. Every surface that renders
 * a memory, including the Memory Garden, reads this field and labels it.
 */
enum class Provenance {
    /** The person's actual voice. */
    AUTHENTIC_RECORDING,

    /** Their handwriting, their photograph, their document. */
    AUTHENTIC_DOCUMENT,

    /** A living relative wrote this themselves. */
    HUMAN_WRITTEN,

    /** Machine transcript or machine tagging of authentic source material. */
    AI_TRANSCRIBED,

    /** Machine-written connective text. Always visibly labeled, never attributed to a person. */
    AI_ORGANIZED
}

enum class UploadState { LOCAL_ONLY, UPLOADING, SYNCED, FAILED }

enum class TranscriptStatus { NONE, PENDING, RUNNING, READY, FAILED }

enum class OutboxOp { CREATE, UPDATE, DELETE, UPLOAD }

enum class PromptOrigin { LIBRARY, GAP_DETECTED, USER }

enum class PromptStatus { SUGGESTED, SAVED, ANSWERED, SKIPPED }

/** A living profile becomes a memorial without losing anything the person chose. */
enum class ProfileState { LIVING, MEMORIAL }

enum class RelationshipKind {
    PARENT, CHILD, SIBLING, SPOUSE, PARTNER,
    GRANDPARENT, GRANDCHILD, AUNT_UNCLE, NIECE_NEPHEW, COUSIN, CHOSEN, OTHER
}

/** Which archive a librarian question is being asked against. */
enum class LibrarianScope {
    /** Everything this user owns, including their private memories. */
    PERSONAL,

    /** Only what family members deliberately shared. */
    FAMILY
}

/**
 * A person in the family.
 *
 * Not the same thing as an account. A great-grandmother has a profile and no login; a
 * six-year-old has neither. [linkedUserId] is what connects a profile to a real member.
 */
data class Person(
    val personId: String,
    val displayName: String,
    val alsoKnownAs: List<String> = emptyList(),
    val birthYear: Int? = null,
    val deathYear: Int? = null,
    val birthPlace: String? = null,
    val relationLabel: String? = null,
    val linkedUserId: String? = null,
    val state: ProfileState = ProfileState.LIVING,
    /**
     * Who maintains this profile once the person no longer can. Named by the person
     * themselves while they are living, wherever possible.
     */
    val memoryStewardUserId: String? = null,
    val consentGranted: Boolean = false,
    val postMortemOk: Boolean = false
) {
    val isDeceased: Boolean get() = deathYear != null || state == ProfileState.MEMORIAL
}

/** An edge in the family tree. Stored once, rendered from both directions. */
data class Relationship(
    val fromPersonId: String,
    val toPersonId: String,
    val kind: RelationshipKind,
    /** Marked by a keeper when a link is disputed, rather than deleting it. */
    val uncertain: Boolean = false
)

data class Story(
    val storyId: String,
    val title: String,
    val kind: StoryKind,
    val area: ArchiveArea = ArchiveArea.STORIES,
    val narratorIds: List<String> = emptyList(),
    /**
     * Who this record is ABOUT, as opposed to who created it.
     *
     * For most memories this is the same as the narrator. For [ArchiveArea.HEALTH] it is
     * the person whose body the record describes, and it is the field that decides who
     * controls the record.
     */
    val subjectPersonIds: List<String> = emptyList(),
    val eraStart: Int? = null,
    val eraEnd: Int? = null,
    val eraPrecision: EraPrecision = EraPrecision.UNKNOWN,
    val placeLabel: String? = null,
    val tags: List<String> = emptyList(),
    val visibility: Visibility = Visibility.FAMILY,
    val aiUsePolicy: AiUsePolicy = AiUsePolicy.SUMMARY_OK,
    val provenance: Provenance = Provenance.AUTHENTIC_RECORDING,
    /** Explicitly named people, used when visibility is SELECTED. */
    val sharedWithUserIds: List<String> = emptyList(),
    val restricted: Boolean = false,
    val durationMs: Long = 0L,
    val assetCount: Int = 0,
    val transcriptStatus: TranscriptStatus = TranscriptStatus.NONE,
    val uploadState: UploadState = UploadState.LOCAL_ONLY,
    val createdBy: String = "",
    val createdAt: Long = 0L
) {
    /** "1958 to 1964", "1953", or "Year unknown". Never a fabricated exact date. */
    val eraLabel: String
        get() = when {
            eraPrecision == EraPrecision.UNKNOWN || eraStart == null -> "Year unknown"
            eraEnd != null && eraEnd != eraStart -> "$eraStart to $eraEnd"
            else -> eraStart.toString()
        }

    /** Which decade bucket this belongs in on the timeline. Null when we genuinely don't know. */
    val decade: Int?
        get() = eraStart?.let { (it / 10) * 10 }

    /** True for anything a person actually said, wrote, or photographed. */
    val isAuthentic: Boolean
        get() = provenance == Provenance.AUTHENTIC_RECORDING ||
            provenance == Provenance.AUTHENTIC_DOCUMENT ||
            provenance == Provenance.HUMAN_WRITTEN

    /**
     * An ordinary update today is archive material in thirty years. That is the whole
     * reason a feed belongs inside a preservation tool, so nothing here is second-class.
     */
    val isEverydayUpdate: Boolean get() = kind == StoryKind.UPDATE
}

data class TranscriptSegment(
    val id: Long = 0L,
    val assetId: String,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val confidence: Float = 0f,
    /** True once a human corrected this line. Provenance: machine versus verified. */
    val humanVerified: Boolean = false
)

data class Prompt(
    val promptId: String,
    val text: String,
    val category: String,
    val targetPersonId: String? = null,
    val origin: PromptOrigin = PromptOrigin.LIBRARY,
    /** Why the app is asking this now. Shown to the user, never a black box. */
    val rationale: String? = null,
    val status: PromptStatus = PromptStatus.SUGGESTED
)

/**
 * One answer from a librarian.
 *
 * [sources] is not decoration. An answer with no sources must not render, because the
 * difference between "Grandpa said this" and "a model wrote this" is the entire
 * trustworthiness of the archive.
 */
data class LibrarianAnswer(
    val question: String,
    val scope: LibrarianScope,
    val text: String,
    val sources: List<LibrarianSource>,
    /** Memories that matched but were withheld by permission. Counted, never named. */
    val withheldCount: Int = 0,
    /**
     * Names of the hive shelves the answer came through, in nomination order. Empty when
     * a flat librarian answered. Shown to the user: retrieval that cannot explain itself
     * has no place in an archive built on provenance.
     */
    val routedThrough: List<String> = emptyList(),
    /** Set by the clinical guard. Drives the disclosure the UI must render. */
    val medicalRecordsPresent: Boolean = false
) {
    val isGrounded: Boolean get() = sources.isNotEmpty()
}

data class LibrarianSource(
    val storyId: String,
    val personId: String?,
    val quote: String,
    val startMs: Long?,
    val provenance: Provenance,
    val area: ArchiveArea = ArchiveArea.STORIES
)
