package com.arv.app.core.sync

import com.arv.app.core.data.local.MemberEntity
import com.arv.app.core.data.local.PersonEntity
import com.arv.app.core.data.local.RelationshipEntity
import com.arv.app.core.data.local.StoryEntity
import com.arv.app.core.model.ArchiveArea
import com.arv.app.core.model.MemberRole
import com.arv.app.core.model.RelationshipKind
import com.arv.app.core.model.StoryKind
import com.arv.app.core.model.TranscriptStatus
import com.arv.app.core.model.UploadState
import com.arv.app.core.model.Visibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a pull may change on a phone. Most of these are about what it must not: overwrite a
 * newer edit, take in something private, or read a missing document as a deleted one.
 */
class SyncMergeTest {

    private val fam = "fam_1"
    private val me = "u_dana"

    private fun story(
        id: String = "s_1",
        createdBy: String = "u_ruth",
        updatedAt: Long = 100L,
        syncedAt: Long? = updatedAt,
        title: String = "The night the levee broke",
        visibility: Visibility = Visibility.FAMILY,
        area: ArchiveArea = ArchiveArea.STORIES,
        family: String = fam
    ) = StoryEntity(
        storyId = id,
        familyId = family,
        title = title,
        kind = StoryKind.AUDIO,
        area = area,
        visibility = visibility,
        createdBy = createdBy,
        updatedAt = updatedAt,
        syncedAt = syncedAt
    )

    private fun person(id: String = "p_ruth", updatedAt: Long = 100L, syncedAt: Long? = updatedAt, name: String = "Ruth Delaney") =
        PersonEntity(personId = id, familyId = fam, displayName = name, updatedAt = updatedAt, syncedAt = syncedAt)

    private fun edge(from: String = "p_ruth", to: String = "p_walt", updatedAt: Long = 100L, syncedAt: Long? = updatedAt) =
        RelationshipEntity(familyId = fam, fromPersonId = from, toPersonId = to, kind = RelationshipKind.PARENT, updatedAt = updatedAt, syncedAt = syncedAt)

    private fun member(userId: String, role: MemberRole = MemberRole.CONTRIBUTOR) =
        MemberEntity(familyId = fam, userId = userId, role = role, joinedAt = 10L)

    /** What the server sends. Documents come without this phone's bookkeeping, as SyncDocs reads them. */
    private fun got(
        stories: List<StoryEntity> = emptyList(),
        people: List<PersonEntity> = emptyList(),
        edges: List<RelationshipEntity> = emptyList(),
        members: List<MemberEntity> = listOf(member(me)),
        unreadableStoryIds: Set<String> = emptySet()
    ) = Fetched.Got(
        members = members,
        memberIds = members.map { it.userId }.toSet(),
        people = people.map { it.copy(syncedAt = null, refusedAt = null, relationLabel = null, portraitPath = null) },
        relationships = edges.map { it.copy(syncedAt = null, refusedAt = null) },
        relationshipIds = edges.map { SyncDocs.edgeId(it) }.toSet(),
        stories = stories.map { it.copy(syncedAt = null, refusedAt = null, transcriptStatus = TranscriptStatus.NONE, uploadState = UploadState.LOCAL_ONLY) },
        storyIds = stories.map { it.storyId }.toSet() + unreadableStoryIds
    )

    private fun local(
        stories: List<StoryEntity> = emptyList(),
        people: List<PersonEntity> = emptyList(),
        edges: List<RelationshipEntity> = emptyList(),
        members: List<MemberEntity> = listOf(member(me)),
        removingEdgeIds: Set<String> = emptySet()
    ) = SyncMerge.Local(stories, people, edges, members, removingEdgeIds)

    private fun plan(local: SyncMerge.Local, got: Fetched.Got) = SyncMerge.plan(local, got, fam, me)

    // --- stories arriving

    @Test
    fun `a story the family added arrives marked as held by the server, with nothing to transcribe yet`() {
        val p = plan(local(), got(stories = listOf(story(updatedAt = 300L))))
        val arrived = p.writeStories.single()
        assertEquals(300L, arrived.syncedAt)
        assertEquals(TranscriptStatus.NONE, arrived.transcriptStatus)
        assertEquals(UploadState.LOCAL_ONLY, arrived.uploadState)
    }

    @Test
    fun `a later edit from another phone replaces this copy but keeps what this phone worked out`() {
        val mine = story(updatedAt = 100L).copy(transcriptStatus = TranscriptStatus.READY)
        val theirs = story(updatedAt = 200L, title = "The levee, 1927")
        val written = plan(local(stories = listOf(mine)), got(stories = listOf(theirs))).writeStories.single()
        assertEquals("The levee, 1927", written.title)
        assertEquals(TranscriptStatus.READY, written.transcriptStatus)
        assertEquals(200L, written.syncedAt)
    }

    @Test
    fun `an edit on this phone that has not gone yet is not overwritten by an older copy`() {
        val mine = story(updatedAt = 300L, syncedAt = 100L, title = "Fixed title")
        val theirs = story(updatedAt = 100L, title = "Old title")
        assertTrue(plan(local(stories = listOf(mine)), got(stories = listOf(theirs))).writeStories.isEmpty())
    }

    @Test
    fun `an edit the rules refused gives way to the family's copy, even though it is newer`() {
        val refused = story(updatedAt = 900L, syncedAt = 100L, title = "Not accepted").copy(refusedAt = 900L)
        val written = plan(local(stories = listOf(refused)), got(stories = listOf(story(updatedAt = 100L)))).writeStories.single()
        assertEquals("The night the levee broke", written.title)
        assertEquals(100L, written.updatedAt)
        assertEquals(100L, written.syncedAt)
        assertEquals(null, written.refusedAt)

        val person = person(updatedAt = 900L, syncedAt = 100L, name = "Not accepted").copy(refusedAt = 900L)
        assertEquals("Ruth Delaney", plan(local(people = listOf(person)), got(people = listOf(person()))).writePeople.single().displayName)
    }

    @Test
    fun `an edit made after a refusal is an edit again, and the later one still wins`() {
        val editedAgain = story(updatedAt = 950L, syncedAt = 100L, title = "Tried again").copy(refusedAt = 900L)
        assertTrue(plan(local(stories = listOf(editedAgain)), got(stories = listOf(story(updatedAt = 100L)))).writeStories.isEmpty())
    }

    @Test
    fun `the same version on both sides only records that the server has it`() {
        val mine = story(updatedAt = 200L, syncedAt = null)
        val written = plan(local(stories = listOf(mine)), got(stories = listOf(story(updatedAt = 200L)))).writeStories.single()
        assertEquals(mine.copy(syncedAt = 200L), written)
    }

    @Test
    fun `a private story or a health record is never taken in, whoever put it there`() {
        val p = plan(
            local(),
            got(stories = listOf(
                story(id = "s_private", visibility = Visibility.PRIVATE, createdBy = me),
                story(id = "s_health", area = ArchiveArea.HEALTH)
            ))
        )
        assertTrue(p.writeStories.isEmpty())
    }

    @Test
    fun `a story filed under another family is never taken in`() {
        assertTrue(plan(local(), got(stories = listOf(story(family = "fam_2")))).writeStories.isEmpty())
    }

    @Test
    fun `a delete from another phone arrives as a delete, not a removal`() {
        val mine = story(updatedAt = 100L)
        val theirs = story(updatedAt = 250L).copy(deletedAt = 250L, deletedBy = "u_kev")
        val p = plan(local(stories = listOf(mine)), got(stories = listOf(theirs)))
        assertEquals(250L, p.writeStories.single().deletedAt)
        assertTrue("still restorable, so the row stays", p.removeStories.isEmpty())
    }

    // --- stories going

    @Test
    fun `somebody else's story that stopped arriving is let go`() {
        val p = plan(local(stories = listOf(story(createdBy = "u_ruth"))), got())
        assertEquals(listOf("s_1"), p.removeStories)
    }

    @Test
    fun `nothing made here and never sent is removed, however empty the answer`() {
        val p = plan(
            local(
                stories = listOf(story(id = "s_old", createdBy = "u_legacy", syncedAt = null)),
                people = listOf(person(syncedAt = null)),
                edges = listOf(edge(syncedAt = null))
            ),
            got()
        )
        assertTrue(p.removeStories.isEmpty())
        assertTrue(p.removeRelationships.isEmpty())
    }

    @Test
    fun `your own story is never removed for being absent`() {
        assertTrue(plan(local(stories = listOf(story(createdBy = me))), got()).removeStories.isEmpty())
    }

    @Test
    fun `a story with an edit still waiting to go stays until the edit has been sent`() {
        val keeperEdit = story(createdBy = "u_ruth", updatedAt = 150L, syncedAt = 100L)
        assertTrue(plan(local(stories = listOf(keeperEdit)), got()).removeStories.isEmpty())
    }

    @Test
    fun `a document this version could not read is still there, so its story stays`() {
        val p = plan(local(stories = listOf(story(createdBy = "u_ruth"))), got(unreadableStoryIds = setOf("s_1")))
        assertTrue(p.removeStories.isEmpty())
    }

    // --- people, links, members

    @Test
    fun `a newer copy of a person keeps this phone's face for them and its own words for them`() {
        val mine = person(updatedAt = 100L).copy(portraitPath = "/portraits/ruth.jpg", relationLabel = "Grandma")
        val theirs = person(updatedAt = 200L, name = "Ruth A. Delaney")
        val written = plan(local(people = listOf(mine)), got(people = listOf(theirs))).writePeople.single()
        assertEquals("Ruth A. Delaney", written.displayName)
        assertEquals("/portraits/ruth.jpg", written.portraitPath)
        assertEquals("Grandma", written.relationLabel)
        assertEquals(200L, written.syncedAt)
    }

    @Test
    fun `a link a keeper removed goes from this phone too`() {
        val p = plan(local(edges = listOf(edge())), got())
        assertEquals(listOf(edge()), p.removeRelationships)
    }

    @Test
    fun `a link removed here is not pulled back before the server has heard about the removal`() {
        val removed = edge()
        val p = plan(
            local(removingEdgeIds = setOf(SyncDocs.edgeId(removed))),
            got(edges = listOf(removed))
        )
        assertTrue(p.writeRelationships.isEmpty())
    }

    @Test
    fun `the member list follows the server, and an unchanged row is not rewritten`() {
        val owner = member("u_ruth", MemberRole.OWNER)
        val p = plan(
            local(members = listOf(member(me), owner, member("u_gone"))),
            got(members = listOf(member(me), owner, member("u_new")))
        )
        assertEquals(listOf("u_new"), p.writeMembers.map { it.userId })
        assertEquals(listOf("u_gone"), p.removeMembers)
    }

    @Test
    fun `a pull that brings nothing new changes nothing`() {
        val s = story()
        val p = plan(local(stories = listOf(s), people = listOf(person()), edges = listOf(edge())), got(listOf(s), listOf(person()), listOf(edge())))
        assertFalse(p.changesAnything)
    }
}
