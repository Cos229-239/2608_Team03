package com.arv.app.core.sync

import com.arv.app.core.data.local.MemberEntity
import com.arv.app.core.data.local.AssetEntity
import com.arv.app.core.data.local.OutboxEntity
import com.arv.app.core.data.local.PersonEntity
import com.arv.app.core.data.local.RelationshipEntity
import com.arv.app.core.data.local.StoryEntity
import com.arv.app.core.model.ArchiveArea
import com.arv.app.core.model.MemberRole
import com.arv.app.core.model.OutboxOp
import com.arv.app.core.model.RelationshipKind
import com.arv.app.core.model.AssetType
import com.arv.app.core.model.StoryKind
import com.arv.app.core.model.UploadState
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

    /**
     * A real file on disk, because the engine will not send bytes it cannot find. Written to
     * the JVM's temp folder and deleted when the JVM exits, so nothing is left behind.
     */
    private fun recording(name: String): java.io.File =
        java.io.File.createTempFile(name, ".m4a").apply { writeBytes(ByteArray(32) { 7 }); deleteOnExit() }

    private fun asset(
        id: String,
        storyId: String,
        localPath: String,
        remotePath: String? = null,
        state: UploadState = UploadState.LOCAL_ONLY
    ) = AssetEntity(
        assetId = id, storyId = storyId, familyId = fam, type = AssetType.AUDIO,
        localPath = localPath, remotePath = remotePath, mimeType = "audio/mp4",
        uploadState = state, createdAt = 10L
    )

    /** A phone's database. Holds rows and applies what it is told, the way Room would. */
    private class FakeLocal(
        stories: List<StoryEntity> = emptyList(),
        people: List<PersonEntity> = emptyList(),
        edges: List<RelationshipEntity> = emptyList(),
        members: List<MemberEntity> = emptyList(),
        removals: List<OutboxEntity> = emptyList(),
        storyRemovals: List<OutboxEntity> = emptyList(),
        assets: List<AssetEntity> = emptyList()
    ) : SyncLocal {
        val assets = assets.associateBy { it.assetId }.toMutableMap()
        val downloads = mutableListOf<String>()
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

        override suspend fun unsentAssets(familyId: String) =
            assets.values
                .filter { it.uploadState != UploadState.SYNCED }
                .mapNotNull { a -> stories[a.storyId]?.let { AssetWork(a, it) } }

        override suspend fun assetRecorded(assetId: String, remotePath: String) {
            assets[assetId] = assets.getValue(assetId).copy(remotePath = remotePath, uploadState = UploadState.UPLOADING)
        }

        override suspend fun assetUploaded(assetId: String) {
            assets[assetId] = assets.getValue(assetId).copy(uploadState = UploadState.SYNCED)
        }

        override suspend fun assetRefused(assetId: String) {
            assets[assetId] = assets.getValue(assetId).copy(uploadState = UploadState.FAILED)
        }

        override suspend fun assetsToFetch(familyId: String) =
            assets.values.filter { it.remotePath != null && it.localPath.isBlank() }

        override fun fileFor(asset: AssetEntity): java.io.File =
            java.io.File(System.getProperty("java.io.tmpdir"), "arv-test-" + asset.assetId)

        override suspend fun assetArrived(assetId: String, localPath: String) {
            downloads += assetId
            assets[assetId] = assets.getValue(assetId).copy(localPath = localPath, uploadState = UploadState.SYNCED)
        }

        override suspend fun merge(familyId: String, plan: (SyncMerge.Local) -> SyncMerge.Plan): SyncMerge.Plan {
            val p = plan(
                SyncMerge.Local(
                    stories.values.toList(), people.values.toList(), edges.values.toList(),
                    members.values.toList(), removals.map { it.docId }.toSet(),
                    assets.values.toList()
                )
            )
            p.writeAssets.forEach { assets[it.assetId] = it }
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
        val assetDocs = mutableMapOf<String, AssetEntity>()
        val uploaded = mutableMapOf<String, Long>()
        var assetDocAnswer: Sent = Sent.Done
        var uploadAnswer: Sent = Sent.Done
        var downloadAnswer: Sent = Sent.Done

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

        override suspend fun sendAsset(asset: AssetEntity, story: StoryEntity): Sent {
            calls += "assetdoc:${asset.assetId}"
            if (assetDocAnswer == Sent.Done) assetDocs[asset.assetId] = asset
            return assetDocAnswer
        }

        override suspend fun uploadAssetFile(remotePath: String, file: java.io.File): Sent {
            calls += "upload:$remotePath"
            if (uploadAnswer == Sent.Done) uploaded[remotePath] = file.length()
            return uploadAnswer
        }

        override suspend fun downloadAssetFile(remotePath: String, into: java.io.File): Sent {
            calls += "download:$remotePath"
            if (downloadAnswer == Sent.Done) {
                into.parentFile?.mkdirs()
                into.writeBytes(ByteArray(32) { 7 })
                into.deleteOnExit()
            }
            return downloadAnswer
        }

        override suspend fun removeAssetFile(remotePath: String): Sent {
            calls += "rmfile:$remotePath"
            uploaded.remove(remotePath)
            return Sent.Done
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
                storyIds = stories.keys.toSet(),
                assets = assetDocs.values.toList(),
                assetIds = assetDocs.keys.toSet()
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

    // --- recordings and photographs ---

    @Test
    fun `a recording goes up as a record first and bytes second, because the rules read the record`() = runBlocking {
        val file = recording("levee")
        val local = FakeLocal(
            stories = listOf(story("s_1", visibility = Visibility.FAMILY)),
            assets = listOf(asset("a_1", "s_1", file.path))
        )
        val remote = FakeRemote()

        engine(local, remote).run(fam, me, pull = false)

        val path = "families/$fam/assets/a_1/file.m4a"
        assertEquals(listOf("story:s_1:new", "assetdoc:a_1", "upload:$path"), remote.calls)
        assertEquals(file.length(), remote.uploaded[path])
        assertEquals(UploadState.SYNCED, local.assets.getValue("a_1").uploadState)
        assertEquals(path, local.assets.getValue("a_1").remotePath)
    }

    @Test
    fun `the recording of a private story never leaves the phone, and neither does a health one`() = runBlocking {
        val file = recording("private")
        val local = FakeLocal(
            stories = listOf(
                story("s_priv", visibility = Visibility.PRIVATE),
                story("s_health", area = ArchiveArea.HEALTH)
            ),
            assets = listOf(asset("a_priv", "s_priv", file.path), asset("a_health", "s_health", file.path))
        )
        val remote = FakeRemote()

        engine(local, remote).run(fam, me, pull = false)

        assertTrue("nothing about a private or health file was sent", remote.calls.isEmpty())
        assertTrue(remote.assetDocs.isEmpty())
        assertTrue(remote.uploaded.isEmpty())
    }

    @Test
    fun `a record the rules refuse is never followed by its bytes`() = runBlocking {
        val file = recording("refused")
        val local = FakeLocal(stories = listOf(story("s_1")), assets = listOf(asset("a_1", "s_1", file.path)))
        val remote = FakeRemote().apply { assetDocAnswer = Sent.Refused }

        engine(local, remote).run(fam, me, pull = false)

        assertTrue("no upload was attempted", remote.calls.none { it.startsWith("upload:") })
        assertEquals(UploadState.FAILED, local.assets.getValue("a_1").uploadState)
    }

    @Test
    fun `a file that is not on this phone any more is passed over, not failed`() = runBlocking {
        val local = FakeLocal(
            stories = listOf(story("s_1")),
            assets = listOf(asset("a_gone", "s_1", "/nowhere/gone.m4a"))
        )
        val remote = FakeRemote()

        engine(local, remote).run(fam, me, pull = false)

        assertTrue(remote.calls.none { it.startsWith("assetdoc:") || it.startsWith("upload:") })
        assertEquals(UploadState.LOCAL_ONLY, local.assets.getValue("a_gone").uploadState)
    }

    @Test
    fun `a record that already landed is not sent twice when the bytes had to wait`() = runBlocking {
        val file = recording("waiting")
        val local = FakeLocal(
            stories = listOf(story("s_1")),
            assets = listOf(
                asset("a_1", "s_1", file.path, remotePath = "families/$fam/assets/a_1/file.m4a", state = UploadState.UPLOADING)
            )
        )
        val remote = FakeRemote()

        engine(local, remote).run(fam, me, pull = false)

        assertTrue("the record was already there", remote.calls.none { it.startsWith("assetdoc:") })
        assertTrue(remote.calls.any { it.startsWith("upload:") })
        assertEquals(UploadState.SYNCED, local.assets.getValue("a_1").uploadState)
    }

    @Test
    fun `a pull brings down the file behind a record that just arrived`() = runBlocking {
        val local = FakeLocal()
        val remote = FakeRemote()
        remote.stories["s_theirs"] = story("s_theirs", createdBy = "u_ruth")
        remote.assetDocs["a_theirs"] =
            asset("a_theirs", "s_theirs", localPath = "", remotePath = "families/$fam/assets/a_theirs/file.m4a")

        engine(local, remote).run(fam, me, pull = true)

        assertEquals(listOf("a_theirs"), local.downloads)
        assertTrue(local.assets.getValue("a_theirs").localPath.isNotBlank())
        assertEquals(UploadState.SYNCED, local.assets.getValue("a_theirs").uploadState)
    }

    @Test
    fun `a file that will not come down does not undo the records that did`() = runBlocking {
        val local = FakeLocal()
        val remote = FakeRemote().apply { downloadAnswer = Sent.Unreachable }
        remote.stories["s_theirs"] = story("s_theirs", createdBy = "u_ruth")
        remote.assetDocs["a_theirs"] =
            asset("a_theirs", "s_theirs", localPath = "", remotePath = "families/$fam/assets/a_theirs/file.m4a")

        val result = engine(local, remote).run(fam, me, pull = true)

        assertTrue("the pull still counts as done", result is SyncEngine.Result.Done)
        assertTrue("the story arrived", "s_theirs" in local.stories)
        assertTrue("and so did the record of its file", "a_theirs" in local.assets)
        assertTrue("only the bytes are still missing", local.downloads.isEmpty())
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
