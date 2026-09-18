package com.arv.app.core.sync

import com.arv.app.core.data.local.MemberEntity
import com.arv.app.core.data.local.OutboxEntity
import com.arv.app.core.data.local.PersonEntity
import com.arv.app.core.data.local.RelationshipEntity
import com.arv.app.core.data.local.StoryEntity
import com.arv.app.core.model.ArchiveArea
import com.arv.app.core.model.MemberRole
import com.arv.app.core.model.OutboxOp
import com.arv.app.core.model.RelationshipKind
import com.arv.app.core.model.StoryKind
import com.arv.app.core.model.Visibility
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncEngineTest {

    private val fam = "fam_1"
    private val me = "u_dana"

    private fun story(
        id: String,
        visibility: Visibility = Visibility.FAMILY,
        area: ArchiveArea = ArchiveArea.STORIES,
        createdBy: String = me,
        updatedAt: Long = 100L,
        syncedAt: Long? = null,
        title: String = "A story"
    ) = StoryEntity(
        storyId = id, familyId = fam, title = title, kind = StoryKind.AUDIO, area = area,
        visibility = visibility, createdBy = createdBy, updatedAt = updatedAt, syncedAt = syncedAt
    )

    /** A phone's database. Holds rows and applies what it is told, the way Room would. */
    private class FakeLocal(
        stories: List<StoryEntity> = emptyList(),
        people: List<PersonEntity> = emptyList(),
        edges: List<RelationshipEntity> = emptyList(),
        members: List<MemberEntity> = emptyList(),
        removals: List<OutboxEntity> = emptyList(),
        storyRemovals: List<OutboxEntity> = emptyList()
    ) : SyncLocal {
        val stories = stories.associateBy { it.storyId }.toMutableMap()
        val people = people.associateBy { it.personId }.toMutableMap()
        val edges = edges.associateBy { SyncDocs.edgeId(it) }.toMutableMap()
        val members = members.associateBy { it.userId }.toMutableMap()
        val removals = removals.toMutableList()
        val storyRemovals = storyRemovals.toMutableList()

        /** Runs while a story is being sent, to stand in for an edit made at that moment. */
        var duringSend: (() -> Unit)? = null

        private fun unsent(updatedAt: Long, syncedAt: Long?) = syncedAt == null || syncedAt != updatedAt

        override suspend fun unsyncedStories(familyId: String) = stories.values.filter { unsent(it.updatedAt, it.syncedAt) }
        override suspend fun unsyncedPeople(familyId: String) = people.values.filter { unsent(it.updatedAt, it.syncedAt) }
        override suspend fun unsyncedRelationships(familyId: String) = edges.values.filter { unsent(it.updatedAt, it.syncedAt) }
        override suspend fun pendingRelationshipRemovals(familyId: String) = removals.toList()
        override suspend fun pendingStoryRemovals(familyId: String) = storyRemovals.toList()

        override suspend fun storySent(storyId: String, updatedAt: Long?) {
            stories[storyId]?.let { stories[storyId] = it.copy(syncedAt = updatedAt) }
        }
        override suspend fun storyRefused(storyId: String, updatedAt: Long) {
            stories[storyId]?.let { stories[storyId] = it.copy(refusedAt = updatedAt) }
        }
        override suspend fun personSent(personId: String, updatedAt: Long) {
            people[personId]?.let { people[personId] = it.copy(syncedAt = updatedAt) }
        }
        override suspend fun personRefused(personId: String, updatedAt: Long) {
            people[personId]?.let { people[personId] = it.copy(refusedAt = updatedAt) }
        }
        override suspend fun relationshipSent(edge: RelationshipEntity) {
            val id = SyncDocs.edgeId(edge)
            edges[id]?.let { edges[id] = it.copy(syncedAt = edge.updatedAt) }
        }
        override suspend fun relationshipRefused(edge: RelationshipEntity) {
            val id = SyncDocs.edgeId(edge)
            edges[id]?.let { edges[id] = it.copy(refusedAt = edge.updatedAt) }
        }
        override suspend fun removalFinished(outboxId: Long) {
            removals.removeAll { it.id == outboxId }
            storyRemovals.removeAll { it.id == outboxId }
        }
        override suspend fun removalFailed(outboxId: Long, why: String) = Unit

        override suspend fun merge(familyId: String, plan: (SyncMerge.Local) -> SyncMerge.Plan): SyncMerge.Plan {
            val p = plan(
                SyncMerge.Local(
                    stories.values.toList(), people.values.toList(), edges.values.toList(),
                    members.values.toList(), removals.map { it.docId }.toSet()
                )
            )
            p.writeStories.forEach { stories[it.storyId] = it }
            p.removeStories.forEach { stories.remove(it) }
            p.writePeople.forEach { people[it.personId] = it }
            p.writeRelationships.forEach { edges[SyncDocs.edgeId(it)] = it }
            p.removeRelationships.forEach { edges.remove(SyncDocs.edgeId(it)) }
            p.writeMembers.forEach { members[it.userId] = it }
            p.removeMembers.forEach { members.remove(it) }
            return p
        }
    }

    /**
     * A family's server. Holds documents, enforces nothing but later-edit-wins, answers what
     * it is told to, and writes down every call in order.
     */
    private class FakeRemote(override val available: Boolean = true) : SyncRemote {
        val calls = mutableListOf<String>()
        val stories = mutableMapOf<String, StoryEntity>()
        val people = mutableMapOf<String, PersonEntity>()
        val edges = mutableMapOf<String, RelationshipEntity>()
        val members = mutableMapOf<String, MemberEntity>()

        var local: FakeLocal? = null
        var storyAnswer: Sent? = null
        var withdrawAnswer: Sent = Sent.Done
        var removeAnswer: Sent = Sent.Done
        var fetchAnswer: Fetched? = null

        override suspend fun sendStory(story: StoryEntity, neverSent: Boolean): Sent {
            calls += "story:${story.storyId}:${if (neverSent) "new" else "checked"}"
            local?.duringSend?.invoke()
            storyAnswer?.let { return it }
            val theirs = stories[story.storyId]
            if (theirs != null && theirs.updatedAt > story.updatedAt) return Sent.Stale
            stories[story.storyId] = story.copy(syncedAt = null, refusedAt = null)
            return Sent.Done
        }

        override suspend fun withdrawStory(familyId: String, storyId: String): Sent {
            calls += "withdraw:$storyId"
            if (withdrawAnswer == Sent.Done) stories.remove(storyId)
            return withdrawAnswer
        }

        override suspend fun sendPerson(person: PersonEntity): Sent {
            calls += "person:${person.personId}"
            people[person.personId] = person.copy(syncedAt = null, refusedAt = null)
            return Sent.Done
        }

        override suspend fun sendRelationship(edge: RelationshipEntity): Sent {
            calls += "edge:${SyncDocs.edgeId(edge)}"
            edges[SyncDocs.edgeId(edge)] = edge.copy(syncedAt = null, refusedAt = null)
            return Sent.Done
        }

        override suspend fun removeRelationship(familyId: String, edgeId: String): Sent {
            calls += "removeEdge:$edgeId"
            if (removeAnswer == Sent.Done) edges.remove(edgeId)
            return removeAnswer
        }

        override suspend fun fetch(familyId: String, userId: String): Fetched {
            calls += "fetch"
            fetchAnswer?.let { return it }
            return Fetched.Got(
                members = members.values.toList(),
                memberIds = members.keys.toSet(),
                people = people.values.toList(),
                relationships = edges.values.toList(),
                relationshipIds = edges.keys.toSet(),
                stories = stories.values.toList(),
                storyIds = stories.keys.toSet()
            )
        }
    }

    private fun engine(local: FakeLocal, remote: FakeRemote): SyncEngine {
        remote.local = local
        remote.members[me] = MemberEntity(familyId = fam, userId = me, role = MemberRole.CONTRIBUTOR, joinedAt = 1L)
        local.members[me] = remote.members.getValue(me)
        return SyncEngine(local, remote)
    }

    @Test
    fun `private stories and health records are never offered to the server`() = runBlocking {
        val local = FakeLocal(
            stories = listOf(
                story("s_family"),
                story("s_private", visibility = Visibility.PRIVATE),
                story("s_health", area = ArchiveArea.HEALTH)
            )
        )
        val remote = FakeRemote()
        engine(local, remote).run(fam, me, pull = false)
        assertEquals(listOf("story:s_family:new"), remote.calls)
        assertEquals(setOf("s_family"), remote.stories.keys)
    }

    @Test
    fun `a sent story is marked with the version that went, so an edit made meanwhile still goes`() = runBlocking {
        val local = FakeLocal(stories = listOf(story("s_1", updatedAt = 100L)))
        val remote = FakeRemote()
        local.duringSend = {
            local.stories["s_1"] = local.stories.getValue("s_1").copy(title = "Edited mid-send", updatedAt = 200L)
            local.duringSend = null
        }
        val sync = engine(local, remote)

        sync.run(fam, me, pull = false)
        assertEquals(100L, local.stories.getValue("s_1").syncedAt)

        sync.run(fam, me, pull = false)
        assertEquals("Edited mid-send", remote.stories.getValue("s_1").title)
        assertEquals(200L, local.stories.getValue("s_1").syncedAt)
    }

    @Test
    fun `a story taken back to private is withdrawn once and then left alone`() = runBlocking {
        val local = FakeLocal(stories = listOf(story("s_1", visibility = Visibility.PRIVATE, updatedAt = 150L, syncedAt = 100L)))
        val remote = FakeRemote().apply { stories["s_1"] = story("s_1") }
        val sync = engine(local, remote)

        sync.run(fam, me, pull = false)
        assertEquals(listOf("withdraw:s_1"), remote.calls)
        assertTrue(remote.stories.isEmpty())
        assertNull(local.stories.getValue("s_1").syncedAt)

        remote.calls.clear()
        sync.run(fam, me, pull = false)
        assertTrue(remote.calls.isEmpty())
    }

    @Test
    fun `a refusal is remembered and not asked again until the story changes`() = runBlocking {
        val local = FakeLocal(stories = listOf(story("s_1", updatedAt = 100L)))
        val remote = FakeRemote().apply { storyAnswer = Sent.Refused }
        val sync = engine(local, remote)

        val first = sync.run(fam, me, pull = false) as SyncEngine.Result.Done
        assertEquals(1, first.refused)
        assertEquals(100L, local.stories.getValue("s_1").refusedAt)

        remote.calls.clear()
        sync.run(fam, me, pull = false)
        assertTrue("the same version is not offered twice", remote.calls.isEmpty())

        local.stories["s_1"] = local.stories.getValue("s_1").copy(updatedAt = 200L)
        sync.run(fam, me, pull = false)
        assertEquals(listOf("story:s_1:new"), remote.calls)
    }

    @Test
    fun `with no connection nothing is marked sent and nothing is pulled`() = runBlocking {
        val local = FakeLocal(stories = listOf(story("s_1")))
        val remote = FakeRemote().apply { storyAnswer = Sent.Unreachable }
        val result = engine(local, remote).run(fam, me, pull = true)
        assertEquals(SyncEngine.Result.Offline, result)
        assertNull(local.stories.getValue("s_1").syncedAt)
        assertTrue("fetch" !in remote.calls)
    }

    @Test
    fun `when the server holds a later edit, that edit is what this phone ends up with`() = runBlocking {
        val local = FakeLocal(stories = listOf(story("s_1", updatedAt = 150L, syncedAt = 100L, title = "Mine")))
        val remote = FakeRemote().apply { stories["s_1"] = story("s_1", updatedAt = 300L, title = "Theirs, later") }
        engine(local, remote).run(fam, me, pull = true)
        assertEquals("story:s_1:checked", remote.calls.first())
        assertEquals("Theirs, later", local.stories.getValue("s_1").title)
        assertEquals(300L, local.stories.getValue("s_1").syncedAt)
    }

    @Test
    fun `a link removed here reaches the server before the pull, so the pull does not bring it back`() = runBlocking {
        val gone = RelationshipEntity(familyId = fam, fromPersonId = "p_a", toPersonId = "p_b", kind = RelationshipKind.PARENT, updatedAt = 100L)
        val removal = OutboxEntity(id = 7L, op = OutboxOp.DELETE, collectionPath = SyncPaths.relationships(fam), docId = SyncDocs.edgeId(gone), payloadJson = "{}")
        val local = FakeLocal(removals = listOf(removal))
        val remote = FakeRemote().apply { edges[SyncDocs.edgeId(gone)] = gone }

        engine(local, remote).run(fam, me, pull = true)

        assertEquals(listOf("removeEdge:${SyncDocs.edgeId(gone)}", "fetch"), remote.calls)
        assertTrue(local.removals.isEmpty())
        assertTrue(local.edges.isEmpty())
    }

    @Test
    fun `a removal only a keeper could make is not retried, and the pull puts the link back`() = runBlocking {
        val kept = RelationshipEntity(familyId = fam, fromPersonId = "p_a", toPersonId = "p_b", kind = RelationshipKind.PARENT, updatedAt = 100L)
        val removal = OutboxEntity(id = 7L, op = OutboxOp.DELETE, collectionPath = SyncPaths.relationships(fam), docId = SyncDocs.edgeId(kept), payloadJson = "{}")
        val local = FakeLocal(removals = listOf(removal))
        val remote = FakeRemote().apply {
            edges[SyncDocs.edgeId(kept)] = kept
            removeAnswer = Sent.Refused
        }

        val result = engine(local, remote).run(fam, me, pull = true) as SyncEngine.Result.Done

        assertEquals(1, result.refused)
        assertTrue(local.removals.isEmpty())
        assertEquals(setOf(SyncDocs.edgeId(kept)), local.edges.keys)
    }

    @Test
    fun `a story erased here is taken off the server before the pull can bring it back`() = runBlocking {
        val erased = story("s_gone", createdBy = "u_ruth", syncedAt = 100L)
        val removal = OutboxEntity(
            id = 3L, op = OutboxOp.DELETE, collectionPath = SyncPaths.stories(fam),
            docId = "s_gone", payloadJson = "{}"
        )
        val local = FakeLocal(storyRemovals = listOf(removal))
        val remote = FakeRemote().apply { stories["s_gone"] = erased }

        engine(local, remote).run(fam, me, pull = true)

        assertEquals(listOf("withdraw:s_gone", "fetch"), remote.calls)
        assertTrue(remote.stories.isEmpty())
        assertTrue(local.storyRemovals.isEmpty())
        assertTrue("nothing comes back", local.stories.isEmpty())
    }

    @Test
    fun `a withdraw the rules refuse is not asked for again`() = runBlocking {
        val removal = OutboxEntity(
            id = 4L, op = OutboxOp.DELETE, collectionPath = SyncPaths.stories(fam),
            docId = "s_gone", payloadJson = "{}"
        )
        val local = FakeLocal(storyRemovals = listOf(removal))
        val remote = FakeRemote().apply { withdrawAnswer = Sent.Refused }

        engine(local, remote).run(fam, me, pull = false)

        assertTrue(local.storyRemovals.isEmpty())
    }

    @Test
    fun `with no connection an erased story stays in the queue`() = runBlocking {
        val removal = OutboxEntity(
            id = 5L, op = OutboxOp.DELETE, collectionPath = SyncPaths.stories(fam),
            docId = "s_gone", payloadJson = "{}"
        )
        val local = FakeLocal(storyRemovals = listOf(removal))
        val remote = FakeRemote().apply { withdrawAnswer = Sent.Unreachable }

        assertEquals(SyncEngine.Result.Offline, engine(local, remote).run(fam, me, pull = true))
        assertEquals(1, local.storyRemovals.size)
        assertTrue("fetch" !in remote.calls)
    }

    @Test
    fun `an account the server does not count as a member changes nothing on this phone`() = runBlocking {
        val theirs = story("s_theirs", createdBy = "u_ruth", syncedAt = 100L)
        val local = FakeLocal(stories = listOf(theirs))
        val remote = FakeRemote().apply { fetchAnswer = Fetched.NotAMember }
        assertEquals(SyncEngine.Result.NotAMember, engine(local, remote).run(fam, me, pull = true))
        assertEquals(theirs, local.stories.getValue("s_theirs"))
    }

    @Test
    fun `a pull that was partly refused is never read as things having gone`() = runBlocking {
        val theirs = story("s_theirs", createdBy = "u_ruth", syncedAt = 100L)
        val local = FakeLocal(stories = listOf(theirs))
        val remote = FakeRemote().apply { fetchAnswer = Fetched.Refused }
        val result = engine(local, remote).run(fam, me, pull = true) as SyncEngine.Result.Done
        assertTrue(result.pullRefused)
        assertEquals(theirs, local.stories.getValue("s_theirs"))
    }

    @Test
    fun `two phones end up holding the same family`() = runBlocking {
        val server = FakeRemote()
        val ruthPhone = FakeLocal(stories = listOf(story("s_levee", createdBy = "u_ruth", title = "The levee")))
        val danaPhone = FakeLocal()
        server.local = ruthPhone
        server.members["u_ruth"] = MemberEntity(familyId = fam, userId = "u_ruth", role = MemberRole.OWNER, joinedAt = 1L)
        server.members[me] = MemberEntity(familyId = fam, userId = me, role = MemberRole.CONTRIBUTOR, joinedAt = 2L)

        SyncEngine(ruthPhone, server).run(fam, "u_ruth", pull = true)
        server.local = danaPhone
        SyncEngine(danaPhone, server).run(fam, me, pull = true)

        assertEquals("The levee", danaPhone.stories.getValue("s_levee").title)
        assertEquals(setOf("u_ruth", me), danaPhone.members.keys)
    }

    @Test
    fun `a build with no server does nothing and says so`() = runBlocking {
        val local = FakeLocal(stories = listOf(story("s_1")))
        val remote = FakeRemote(available = false)
        assertEquals(SyncEngine.Result.NoServer, SyncEngine(local, remote).run(fam, me, pull = true))
        assertTrue(remote.calls.isEmpty())
    }
}
