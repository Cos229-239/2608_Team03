package com.arv.app.core.sync

import com.arv.app.core.data.local.MemberEntity
import com.arv.app.core.data.local.PersonEntity
import com.arv.app.core.data.local.RelationshipEntity
import com.arv.app.core.data.local.StoryEntity
import com.arv.app.core.model.AiUsePolicy
import com.arv.app.core.model.ArchiveArea
import com.arv.app.core.model.Confidence
import com.arv.app.core.model.ConsentMethod
import com.arv.app.core.model.EraPrecision
import com.arv.app.core.model.MemberRole
import com.arv.app.core.model.ProfileState
import com.arv.app.core.model.Provenance
import com.arv.app.core.model.RelationshipKind
import com.arv.app.core.model.StoryKind
import com.arv.app.core.model.Visibility

/** Where each kind of record lives on the server. One place, so the paths never drift. */
object SyncPaths {
    fun stories(familyId: String) = "families/$familyId/stories"
    fun people(familyId: String) = "families/$familyId/people"
    fun relationships(familyId: String) = "families/$familyId/relationships"
    fun members(familyId: String) = "families/$familyId/members"
}

/**
 * The server's document for each row, and the row for each document.
 *
 * Plain maps in and out, so the mapping is tested without Firebase and the Firebase code only
 * moves maps. Every field the rules read is written on every document, including the false
 * and the null ones, because firestore.rules assumes it and a missing field is a refusal.
 *
 * Some columns never leave the phone, and they are left out here rather than filtered later:
 *  - where a file sits on this phone (portraitPath), meaningless on any other;
 *  - how far this phone got transcribing or uploading, which each phone works out itself;
 *  - syncedAt and refusedAt, this phone's own bookkeeping about the server;
 *  - relationLabel. "You", "Grandmother" and "my cousin's wife" are said from where the
 *    person typing stands, so on anybody else's phone they are wrong. Each phone keeps its
 *    own words for people.
 *
 * Reading back, a document missing something required, or naming a value this version of the
 * app does not know, comes back null. It is left alone rather than guessed at.
 */
object SyncDocs {

    // ---- stories

    fun story(s: StoryEntity): Map<String, Any?> = mapOf(
        "storyId" to s.storyId,
        "familyId" to s.familyId,
        "title" to s.title,
        "kind" to s.kind.name,
        "area" to s.area.name,
        "narratorIds" to s.narratorIds,
        "subjectPersonIds" to s.subjectPersonIds,
        "eraStart" to s.eraStart,
        "eraEnd" to s.eraEnd,
        "eraPrecision" to s.eraPrecision.name,
        "placeLabel" to s.placeLabel,
        "tags" to s.tags,
        "visibility" to s.visibility.name,
        "aiUsePolicy" to s.aiUsePolicy.name,
        "provenance" to s.provenance.name,
        "sharedWithUserIds" to s.sharedWithUserIds,
        "restricted" to s.restricted,
        "branchRootPersonId" to s.branchRootPersonId,
        "durationMs" to s.durationMs,
        "assetCount" to s.assetCount,
        "primaryAssetId" to s.primaryAssetId,
        "createdBy" to s.createdBy,
        "createdAt" to s.createdAt,
        "updatedAt" to s.updatedAt,
        "deletedAt" to s.deletedAt,
        "deletedBy" to s.deletedBy
    )

    fun storyFrom(id: String, d: Map<String, Any?>): StoryEntity? {
        return StoryEntity(
            storyId = id,
            familyId = d.string("familyId") ?: return null,
            title = d.string("title") ?: return null,
            kind = d.enum<StoryKind>("kind") ?: return null,
            area = d.enum<ArchiveArea>("area") ?: return null,
            narratorIds = d.strings("narratorIds"),
            subjectPersonIds = d.strings("subjectPersonIds"),
            eraStart = d.int("eraStart"),
            eraEnd = d.int("eraEnd"),
            eraPrecision = d.enum<EraPrecision>("eraPrecision") ?: EraPrecision.UNKNOWN,
            placeLabel = d.string("placeLabel"),
            tags = d.strings("tags"),
            visibility = d.enum<Visibility>("visibility") ?: return null,
            aiUsePolicy = d.enum<AiUsePolicy>("aiUsePolicy") ?: return null,
            provenance = d.enum<Provenance>("provenance") ?: return null,
            sharedWithUserIds = d.strings("sharedWithUserIds"),
            restricted = d["restricted"] as? Boolean ?: return null,
            branchRootPersonId = d.string("branchRootPersonId"),
            durationMs = d.long("durationMs") ?: 0L,
            assetCount = d.int("assetCount") ?: 0,
            primaryAssetId = d.string("primaryAssetId"),
            createdBy = d.string("createdBy") ?: return null,
            createdAt = d.long("createdAt") ?: 0L,
            updatedAt = d.long("updatedAt") ?: return null,
            deletedAt = d.long("deletedAt"),
            deletedBy = d.string("deletedBy")
        )
    }

    // ---- people

    fun person(p: PersonEntity): Map<String, Any?> = mapOf(
        "personId" to p.personId,
        "familyId" to p.familyId,
        "displayName" to p.displayName,
        "alsoKnownAs" to p.alsoKnownAs,
        "birthYear" to p.birthYear,
        "deathYear" to p.deathYear,
        "deathYearEnd" to p.deathYearEnd,
        "birthPlace" to p.birthPlace,
        "linkedUserId" to p.linkedUserId,
        "state" to p.state.name,
        "memoryStewardUserId" to p.memoryStewardUserId,
        "consentGranted" to p.consentGranted,
        "postMortemOk" to p.postMortemOk,
        "confidence" to p.confidence.name,
        "source" to p.source,
        "verifiedAt" to p.verifiedAt,
        "note" to p.note,
        "updatedAt" to p.updatedAt,
        "consentDeclined" to p.consentDeclined,
        "consentDecidedAt" to p.consentDecidedAt,
        "consentMethod" to p.consentMethod?.name,
        "consentRecordedBy" to p.consentRecordedBy
    )

    fun personFrom(id: String, d: Map<String, Any?>): PersonEntity? {
        return PersonEntity(
            personId = id,
            familyId = d.string("familyId") ?: return null,
            displayName = d.string("displayName") ?: return null,
            alsoKnownAs = d.strings("alsoKnownAs"),
            birthYear = d.int("birthYear"),
            deathYear = d.int("deathYear"),
            deathYearEnd = d.int("deathYearEnd"),
            birthPlace = d.string("birthPlace"),
            linkedUserId = d.string("linkedUserId"),
            state = d.enum<ProfileState>("state") ?: return null,
            memoryStewardUserId = d.string("memoryStewardUserId"),
            consentGranted = d["consentGranted"] as? Boolean ?: false,
            postMortemOk = d["postMortemOk"] as? Boolean ?: false,
            // Missing is an older document and reads as the default. A value this version does
            // not know is not rounded to one it does: that could turn a doubt into a claim.
            confidence = d.string("confidence")?.let { enumOrNull<Confidence>(it) ?: return null }
                ?: Confidence.FAMILY_TOLD,
            source = d.string("source"),
            verifiedAt = d.long("verifiedAt"),
            note = d.string("note"),
            updatedAt = d.long("updatedAt") ?: return null,
            consentDeclined = d["consentDeclined"] as? Boolean ?: false,
            consentDecidedAt = d.long("consentDecidedAt"),
            // A method this version cannot read is not the same as no decision, so the person
            // is left out rather than shown as undecided.
            consentMethod = d.string("consentMethod")?.let { enumOrNull<ConsentMethod>(it) ?: return null },
            consentRecordedBy = d.string("consentRecordedBy")
        )
    }

    // ---- family links

    /**
     * One document per edge. A tilde rather than an underscore between the parts, because
     * person ids already contain underscores and two different edges must never share an id.
     */
    fun edgeId(e: RelationshipEntity): String = "${e.fromPersonId}~${e.kind.name}~${e.toPersonId}"

    fun relationship(e: RelationshipEntity): Map<String, Any?> = mapOf(
        "familyId" to e.familyId,
        "fromPersonId" to e.fromPersonId,
        "toPersonId" to e.toPersonId,
        "kind" to e.kind.name,
        "uncertain" to e.uncertain,
        "updatedAt" to e.updatedAt
    )

    fun relationshipFrom(d: Map<String, Any?>): RelationshipEntity? {
        return RelationshipEntity(
            familyId = d.string("familyId") ?: return null,
            fromPersonId = d.string("fromPersonId") ?: return null,
            toPersonId = d.string("toPersonId") ?: return null,
            kind = d.enum<RelationshipKind>("kind") ?: return null,
            uncertain = d["uncertain"] as? Boolean ?: false,
            updatedAt = d.long("updatedAt") ?: return null
        )
    }

    // ---- members, read only: FirebaseInviteRemote is what writes them

    fun memberFrom(familyId: String, userId: String, d: Map<String, Any?>): MemberEntity? {
        return MemberEntity(
            familyId = familyId,
            userId = userId,
            role = d.enum<MemberRole>("role") ?: return null,
            personId = d.string("personId"),
            branchRootPersonId = d.string("branchRootPersonId"),
            joinedAt = d.long("joinedAt") ?: 0L,
            invitedBy = d.string("invitedBy")
        )
    }

    // ---- reading values the way Firestore hands them back: whole numbers as Long, lists as List

    private fun Map<String, Any?>.string(key: String): String? = this[key] as? String

    private fun Map<String, Any?>.long(key: String): Long? = (this[key] as? Number)?.toLong()

    private fun Map<String, Any?>.int(key: String): Int? = (this[key] as? Number)?.toInt()

    private fun Map<String, Any?>.strings(key: String): List<String> =
        (this[key] as? List<*>)?.filterIsInstance<String>() ?: emptyList()

    private inline fun <reified E : Enum<E>> Map<String, Any?>.enum(key: String): E? =
        string(key)?.let { enumOrNull<E>(it) }

    private inline fun <reified E : Enum<E>> enumOrNull(name: String): E? =
        enumValues<E>().firstOrNull { it.name == name }
}
