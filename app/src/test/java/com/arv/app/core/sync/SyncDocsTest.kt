package com.arv.app.core.sync

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
import com.arv.app.core.model.TranscriptStatus
import com.arv.app.core.model.UploadState
import com.arv.app.core.model.Visibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The documents the family's server holds. A field lost here is lost on every phone, and a
 * field that should never leave this phone and does has left it for good.
 */
class SyncDocsTest {

    /** Every field set to something other than its default, so a dropped one shows. */
    private fun story() = StoryEntity(
        storyId = "s_levee",
        familyId = "fam_1",
        title = "The night the levee broke",
        kind = StoryKind.COLLECTION,
        area = ArchiveArea.LINEAGE,
        narratorIds = listOf("p_ruth", "p_walt"),
        subjectPersonIds = listOf("p_walt"),
        eraStart = 1958,
        eraEnd = 1964,
        eraPrecision = EraPrecision.RANGE,
        placeLabel = "Greenville, Mississippi",
        tags = listOf("flood", "move north"),
        visibility = Visibility.SELECTED,
        aiUsePolicy = AiUsePolicy.QUOTE_ONLY,
        provenance = Provenance.AUTHENTIC_DOCUMENT,
        sharedWithUserIds = listOf("u_dana"),
        restricted = true,
        branchRootPersonId = "p_ruth",
        durationMs = 2_700_000L,
        assetCount = 3,
        transcriptStatus = TranscriptStatus.READY,
        uploadState = UploadState.SYNCED,
        primaryAssetId = "a_1",
        createdBy = "u_ruth",
        createdAt = 1_000L,
        updatedAt = 2_000L,
        deletedAt = 1_900L,
        deletedBy = "u_kev",
        syncedAt = 1_500L,
        refusedAt = 1_700L
    )

    private fun person() = PersonEntity(
        personId = "p_ruth",
        familyId = "fam_1",
        displayName = "Ruth Delaney",
        alsoKnownAs = listOf("Ruthie"),
        birthYear = 1931,
        deathYear = 2019,
        deathYearEnd = 2020,
        birthPlace = "Chicago",
        relationLabel = "Grandmother",
        linkedUserId = "u_ruth",
        state = ProfileState.MEMORIAL,
        memoryStewardUserId = "u_steward",
        consentGranted = true,
        postMortemOk = true,
        confidence = Confidence.DOCUMENTED,
        source = "Obituary, Tribune, 2019",
        verifiedAt = 900L,
        note = "Predeceased by her brother",
        updatedAt = 1_000L,
        consentDeclined = false,
        consentDecidedAt = 800L,
        consentMethod = ConsentMethod.ON_RECORDING,
        consentRecordedBy = "u_dana",
        portraitPath = "/data/portraits/ruth.jpg",
        portraitAssetId = "a_wedding",
        syncedAt = 700L,
        refusedAt = 600L
    )

    @Test
    fun `a story survives the trip to the server and back, less what only this phone knows`() {
        val s = story()
        val back = SyncDocs.storyFrom(s.storyId, SyncDocs.story(s))
        assertEquals(
            s.copy(
                transcriptStatus = TranscriptStatus.NONE,
                uploadState = UploadState.LOCAL_ONLY,
                syncedAt = null,
                refusedAt = null
            ),
            back
        )
    }

    @Test
    fun `what only this phone knows never goes in a document`() {
        val storyKeys = SyncDocs.story(story()).keys
        listOf("transcriptStatus", "uploadState", "syncedAt", "refusedAt").forEach {
            assertFalse("$it must stay on the phone", it in storyKeys)
        }
        val personKeys = SyncDocs.person(person()).keys
        listOf("portraitPath", "portraitAssetId", "relationLabel", "syncedAt", "refusedAt").forEach {
            assertFalse("$it must stay on the phone", it in personKeys)
        }
    }

    @Test
    fun `every field the rules read is written, the empty and false ones included`() {
        val bare = StoryEntity(
            storyId = "s_bare",
            familyId = "fam_1",
            title = "Untitled",
            kind = StoryKind.AUDIO,
            createdBy = "u_ruth"
        )
        val doc = SyncDocs.story(bare)
        listOf(
            "familyId", "createdBy", "visibility", "sharedWithUserIds", "branchRootPersonId",
            "restricted", "area", "subjectPersonIds", "aiUsePolicy"
        ).forEach { assertTrue("$it is read by firestore.rules", doc.containsKey(it)) }
        assertEquals(false, doc["restricted"])
        assertNull(doc["branchRootPersonId"])
        assertEquals(emptyList<String>(), doc["sharedWithUserIds"])
    }

    @Test
    fun `whole numbers come back from the server as Long and still read`() {
        val doc = SyncDocs.story(story()).toMutableMap().apply {
            put("eraStart", 1958L)
            put("assetCount", 3L)
        }
        val back = SyncDocs.storyFrom("s_levee", doc)
        assertEquals(1958, back?.eraStart)
        assertEquals(3, back?.assetCount)
    }

    @Test
    fun `a story document naming something this version does not know is left alone`() {
        val doc = SyncDocs.story(story())
        assertNull(SyncDocs.storyFrom("s", doc + ("visibility" to "FRIENDS_OF_FRIENDS")))
        assertNull(SyncDocs.storyFrom("s", doc + ("area" to "FINANCES")))
        assertNull("no AI policy is not permission", SyncDocs.storyFrom("s", doc - "aiUsePolicy"))
        assertNull("no restricted flag is not unrestricted", SyncDocs.storyFrom("s", doc - "restricted"))
        assertNull("no version cannot be compared", SyncDocs.storyFrom("s", doc - "updatedAt"))
    }

    @Test
    fun `a person survives the trip, and this phone's face and words for them stay behind`() {
        val p = person()
        val back = SyncDocs.personFrom(p.personId, SyncDocs.person(p))
        assertEquals(
            p.copy(relationLabel = null, portraitPath = null, portraitAssetId = null, syncedAt = null, refusedAt = null),
            back
        )
    }

    @Test
    fun `a consent method or confidence this version does not know is not rounded to one it does`() {
        val doc = SyncDocs.person(person())
        assertNull(SyncDocs.personFrom("p", doc + ("consentMethod" to "BY_TELEGRAM")))
        assertNull(SyncDocs.personFrom("p", doc + ("confidence" to "RUMOURED")))
        assertEquals(
            "an older document with no confidence reads as the default",
            Confidence.FAMILY_TOLD,
            SyncDocs.personFrom("p", doc - "confidence")?.confidence
        )
    }

    @Test
    fun `a family link survives the trip`() {
        val e = RelationshipEntity(
            familyId = "fam_1",
            fromPersonId = "p_ruth",
            toPersonId = "p_walt",
            kind = RelationshipKind.SPOUSE,
            uncertain = true,
            updatedAt = 400L,
            syncedAt = 300L,
            refusedAt = 200L
        )
        assertEquals(e.copy(syncedAt = null, refusedAt = null), SyncDocs.relationshipFrom(SyncDocs.relationship(e)))
    }

    @Test
    fun `two different links never share a document id, even when person ids hold underscores`() {
        fun edge(from: String, to: String, kind: RelationshipKind = RelationshipKind.PARENT) =
            RelationshipEntity(familyId = "fam_1", fromPersonId = from, toPersonId = to, kind = kind)
        assertNotEquals(
            SyncDocs.edgeId(edge("p_x_PARENT_p_y", "p_z")),
            SyncDocs.edgeId(edge("p_x", "p_y_PARENT_p_z"))
        )
        assertNotEquals(
            SyncDocs.edgeId(edge("p_a", "p_b", RelationshipKind.PARENT)),
            SyncDocs.edgeId(edge("p_a", "p_b", RelationshipKind.SPOUSE))
        )
    }

    @Test
    fun `a member reads back with its role, and a role this version does not know is no member`() {
        val row = SyncDocs.memberFrom(
            "fam_1", "u_kev",
            mapOf("role" to "KEEPER", "personId" to "p_kev", "branchRootPersonId" to null, "joinedAt" to 200L, "invitedBy" to "u_ruth")
        )
        assertEquals(MemberRole.KEEPER, row?.role)
        assertEquals("p_kev", row?.personId)
        assertEquals(200L, row?.joinedAt)
        assertEquals("u_ruth", row?.invitedBy)
        assertNull(SyncDocs.memberFrom("fam_1", "u_kev", mapOf("role" to "ARCHDUKE")))
    }
}
