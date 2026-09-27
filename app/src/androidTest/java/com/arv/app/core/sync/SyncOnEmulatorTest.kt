package com.arv.app.core.sync

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arv.app.core.ai.Viewer
import com.arv.app.core.data.StoryRepository
import com.arv.app.core.data.local.ArvDatabase
import com.arv.app.core.data.local.AssetEntity
import com.arv.app.core.data.local.MemberEntity
import com.arv.app.core.data.local.StoryEntity
import com.arv.app.core.model.AiUsePolicy
import com.arv.app.core.model.ArchiveArea
import com.arv.app.core.model.AssetType
import com.arv.app.core.model.EraPrecision
import com.arv.app.core.model.MemberRole
import com.arv.app.core.model.StoryKind
import com.arv.app.core.model.UploadState
import com.arv.app.core.model.Visibility
import com.arv.app.core.remote.FirebaseInviteRemote
import com.arv.app.core.remote.RemoteRedeem
import com.arv.app.core.remote.RemoteWrite
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.json.JSONObject
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Two phones and a family's server, everything real except that the server is the Firebase
 * emulator on the development machine: Room on each phone, the repository, the Firebase SDK,
 * and firestore.rules as committed. The unit tests prove the decisions with fakes. This proves
 * the wiring, the query shapes and the transactions against the rules they have to get past.
 *
 * Skipped unless asked for, because it needs the emulators running:
 *
 *     firebase emulators:start --only auth,firestore,storage --project <project_id in google-services.json>
 *     adb shell am instrument -w -e firebaseEmulator 10.0.2.2 \
 *         -e class com.arv.app.core.sync.SyncOnEmulatorTest \
 *         com.arv.app.debug.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Each phone is its own named FirebaseApp, so the test accounts never replace the account the
 * app on the device is signed in as, and its own in-memory database, so nothing here touches
 * the archive on the device either.
 */
@RunWith(AndroidJUnit4::class)
class SyncOnEmulatorTest {

    private val host: String? = InstrumentationRegistry.getArguments().getString("firebaseEmulator")
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val phones = mutableListOf<Phone>()
    private val now = System.currentTimeMillis()

    private inner class Phone(label: String, withStorage: Boolean = true) {
        val app: FirebaseApp = FirebaseApp.initializeApp(
            context,
            FirebaseOptions.fromResource(context) ?: error("This build has no Firebase configuration."),
            "sync-$label-" + UUID.randomUUID().toString().take(8)
        )
        val auth: FirebaseAuth = FirebaseAuth.getInstance(app).apply { useEmulator(host!!, AUTH_PORT) }
        val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(app).apply {
            useEmulator(host!!, FIRESTORE_PORT)
            firestoreSettings = FirebaseFirestoreSettings.Builder()
                .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build())
                .build()
        }
        val storage: FirebaseStorage = FirebaseStorage.getInstance(app).apply {
            useEmulator(host!!, STORAGE_PORT)
        }
        val db: ArvDatabase = Room.inMemoryDatabaseBuilder(context, ArvDatabase::class.java).build()
        val repo = StoryRepository(db)
        val invites = FirebaseInviteRemote(firestore)

        /** This phone's own folder, so a file Dana downloads cannot be one Ruth already had. */
        val fileDir: File = File(context.cacheDir, "sync-test-" + UUID.randomUUID().toString().take(8))

        /** Without storage, the app on a project that is not on the paid plan. */
        val sync = SyncEngine(
            RoomSyncLocal(db, fileDir),
            FirestoreSyncRemote(firestore, if (withStorage) storage.reference else null)
        )
        lateinit var uid: String

        suspend fun signUp(email: String): Phone = apply {
            uid = auth.createUserWithEmailAndPassword(email, PASSWORD).awaitTask().user!!.uid
        }

        suspend fun signIn(email: String): Phone = apply {
            uid = auth.signInWithEmailAndPassword(email, PASSWORD).awaitTask().user!!.uid
        }

        fun viewer(familyId: String, role: MemberRole) = Viewer(userId = uid, role = role, familyId = familyId)
    }

    private class Family(val id: String, val ruth: Phone, val dana: Phone, val ruthEmail: String)

    @Before
    fun needsTheEmulators() {
        assumeTrue("Run with -e firebaseEmulator <host>. See the class comment.", host != null)
    }

    @After
    fun putThePhonesAway() {
        phones.forEach { phone ->
            runCatching { phone.auth.signOut() }
            runCatching { phone.db.close() }
            runCatching { phone.app.delete() }
            runCatching { phone.fileDir.deleteRecursively() }
        }
    }

    private fun phone(label: String, withStorage: Boolean = true) = Phone(label, withStorage).also { phones += it }

    private fun email(label: String) = "$label-" + UUID.randomUUID().toString().take(8) + "@example.test"

    /**
     * Ruth makes a family and puts it on the server, the way the worker does for an owner, and
     * Dana joins it with a code, the way the join screen does.
     */
    private suspend fun familyOfTwo(ruthHasStorage: Boolean = true): Family {
        val ruthEmail = email("ruth")
        val ruth = phone("ruth", withStorage = ruthHasStorage).signUp(ruthEmail)
        val dana = phone("dana").signUp(email("dana"))

        val fam = ruth.repo.createFamily("Delaney", "Ruth", now, userId = ruth.uid)
        val owner = ruth.repo.memberRowFor(fam.familyId, ruth.uid)!!
        assertEquals(RemoteWrite.Done, ruth.invites.registerFamily(fam.familyId, "Delaney", owner))

        val code = ruth.repo.inviteCodeFor(fam.familyId, ruth.uid, "Delaney", now)
        assertEquals(RemoteWrite.Done, ruth.invites.publish(code))
        val joined = dana.invites.redeem(code.code, dana.uid, now)
        assertTrue("Dana could not join: $joined", joined is RemoteRedeem.Accepted)
        dana.repo.admitMember(
            MemberEntity(
                familyId = fam.familyId, userId = dana.uid, role = MemberRole.CONTRIBUTOR,
                joinedAt = now, invitedBy = ruth.uid
            )
        )
        return Family(fam.familyId, ruth, dana, ruthEmail)
    }

    /**
     * Asks the Storage emulator itself, past the rules, whether it holds [path]. Through the
     * rules a file that is gone and a file that is only refused look the same; this cannot
     * be fooled that way.
     */
    private fun objectsOnServer(phone: Phone, path: String): Int {
        val url = URL("http://" + host + ":" + STORAGE_PORT + "/storage/v1/b/" + phone.storage.reference.bucket + "/o")
        val body = (url.openConnection() as HttpURLConnection).run {
            try { inputStream.bufferedReader().readText() } finally { disconnect() }
        }
        val items = JSONObject(body).optJSONArray("items") ?: return 0
        return (0 until items.length()).count { items.getJSONObject(it).optString("name") == path }
    }

    private fun recordingOn(phone: Phone): File =
        File(phone.fileDir, "levee.m4a").apply { parentFile?.mkdirs(); writeBytes("the night the levee broke".toByteArray()) }

    /** Ruth shares a recording and Dana's phone downloads it. Returns where it sits on the server. */
    private suspend fun sharedRecording(f: Family, onRuthsPhone: File): String {
        f.ruth.db.storyDao().upsert(story(f.id, "s_levee", f.ruth.uid))
        f.ruth.db.assetDao().upsert(asset("a_levee", "s_levee", f.id, onRuthsPhone.path))
        f.ruth.syncOk(f.id)
        f.dana.syncOk(f.id)
        return f.ruth.db.assetDao().byId("a_levee")!!.remotePath!!
    }

    /** The edit screen's own call, as the story's owner makes it. */
    private suspend fun Phone.changeWhoMaySee(familyId: String, storyId: String, visibility: Visibility, at: Long) =
        repo.updateStoryDetails(
            storyId = storyId, viewer = viewer(familyId, MemberRole.OWNER), title = "Story " + storyId,
            eraText = "", eraUnknown = true, placeLabel = null, tags = emptyList(),
            visibility = visibility, branchRootPersonId = null,
            aiUsePolicy = AiUsePolicy.SUMMARY_OK, nowMillis = at
        )

    private fun asset(id: String, storyId: String, familyId: String, localPath: String) =
        AssetEntity(
            assetId = id, storyId = storyId, familyId = familyId, type = AssetType.AUDIO,
            localPath = localPath, mimeType = "audio/mp4", createdAt = now
        )

    private fun story(familyId: String, id: String, by: String, visibility: Visibility = Visibility.FAMILY, area: ArchiveArea = ArchiveArea.STORIES) =
        StoryEntity(
            storyId = id, familyId = familyId, title = "Story $id", kind = StoryKind.AUDIO,
            area = area, visibility = visibility, createdBy = by, createdAt = now, updatedAt = now
        )

    private suspend fun Phone.syncOk(familyId: String): SyncEngine.Result.Done {
        val result = sync.run(familyId, uid, pull = true)
        assertTrue("sync did not finish: $result", result is SyncEngine.Result.Done)
        return result as SyncEngine.Result.Done
    }

    @Test
    fun aFamilyStoryAndTheTreeReachTheOtherPhone_privateAndHealthNeverLeave() = runBlocking {
        val f = familyOfTwo()
        f.ruth.db.storyDao().upsertAll(
            listOf(
                story(f.id, "s_levee", f.ruth.uid),
                story(f.id, "s_diary", f.ruth.uid, visibility = Visibility.PRIVATE),
                story(f.id, "s_heart", f.ruth.uid, area = ArchiveArea.HEALTH)
            )
        )
        val ruthPerson = f.ruth.repo.memberRowFor(f.id, f.ruth.uid)!!.personId!!
        val walt = f.ruth.repo.addPerson(f.id, "Walt Delaney", nowMillis = now)
        f.ruth.repo.connectParent(f.id, walt, ruthPerson, f.ruth.uid, now)

        val sent = f.ruth.syncOk(f.id)
        assertEquals("the family story, two people and one link", 4, sent.sent)
        f.dana.syncOk(f.id)

        assertEquals(listOf("s_levee"), f.dana.db.storyDao().all(f.id).map { it.storyId })
        assertEquals(setOf("Ruth", "Walt Delaney"), f.dana.db.personDao().all(f.id).map { it.displayName }.toSet())
        assertEquals(1, f.dana.db.relationshipDao().observeAllOnce(f.id).size)
        assertEquals(setOf(f.ruth.uid, f.dana.uid), f.dana.db.memberDao().all(f.id).map { it.userId }.toSet())
        assertNull("Ruth's own word for herself stays on her phone", f.dana.db.personDao().byId(ruthPerson)?.relationLabel)
    }

    @Test
    fun aDeleteHidesEverywhere_bringingItBackReturnsIt_privateTakesItOffTheOtherPhone() = runBlocking {
        val f = familyOfTwo()
        val ruthAsOwner = f.ruth.viewer(f.id, MemberRole.OWNER)
        f.ruth.db.storyDao().upsert(story(f.id, "s_levee", f.ruth.uid))
        f.ruth.syncOk(f.id)
        f.dana.syncOk(f.id)
        assertEquals(listOf("s_levee"), f.dana.db.storyDao().all(f.id).map { it.storyId })

        assertTrue(f.ruth.repo.deleteStory("s_levee", ruthAsOwner, now + 10))
        f.ruth.syncOk(f.id)
        f.dana.syncOk(f.id)
        assertTrue("hidden on Dana's phone", f.dana.db.storyDao().all(f.id).isEmpty())
        assertEquals(listOf("s_levee"), f.dana.db.storyDao().observeDeleted(f.id).first().map { it.storyId })

        assertTrue(f.ruth.repo.restoreStory("s_levee", ruthAsOwner, now + 20))
        f.ruth.syncOk(f.id)
        f.dana.syncOk(f.id)
        assertEquals(listOf("s_levee"), f.dana.db.storyDao().all(f.id).map { it.storyId })

        assertTrue(
            f.ruth.repo.updateStoryDetails(
                storyId = "s_levee", viewer = ruthAsOwner, title = "Story s_levee", eraText = "",
                eraUnknown = true, placeLabel = null, tags = emptyList(), visibility = Visibility.PRIVATE,
                branchRootPersonId = null, aiUsePolicy = AiUsePolicy.SUMMARY_OK, nowMillis = now + 30
            )
        )
        f.ruth.syncOk(f.id)
        f.dana.syncOk(f.id)
        assertNull("gone from Dana's phone", f.dana.db.storyDao().byIdIncludingDeleted("s_levee"))
        assertNotNull("still on Ruth's", f.ruth.db.storyDao().byIdIncludingDeleted("s_levee"))
    }

    @Test
    fun theLaterEditWinsOnEveryPhone_andTheRulesStillHaveTheLastWord() = runBlocking {
        val f = familyOfTwo()
        f.ruth.db.storyDao().upsert(story(f.id, "s_levee", f.ruth.uid))
        f.ruth.syncOk(f.id)
        f.dana.syncOk(f.id)

        // Ruth on a second device, signed in as herself.
        val tablet = phone("ruth-tablet").signIn(f.ruthEmail)
        tablet.db.memberDao().upsert(MemberEntity(familyId = f.id, userId = tablet.uid, role = MemberRole.OWNER, joinedAt = now))
        tablet.syncOk(f.id)
        val ruthAsOwner = f.ruth.viewer(f.id, MemberRole.OWNER)
        val tabletAsOwner = tablet.viewer(f.id, MemberRole.OWNER)

        fun edit(phone: Phone, viewer: Viewer, title: String, at: Long) = runBlocking {
            phone.repo.updateStoryDetails(
                storyId = "s_levee", viewer = viewer, title = title, eraText = "", eraUnknown = true,
                placeLabel = null, tags = emptyList(), visibility = Visibility.FAMILY,
                branchRootPersonId = null, aiUsePolicy = AiUsePolicy.SUMMARY_OK, nowMillis = at
            )
        }
        // The phone edits first but sends last. The tablet's edit is the later one.
        assertTrue(edit(f.ruth, ruthAsOwner, "From the phone", now + 50))
        assertTrue(edit(tablet, tabletAsOwner, "From the tablet", now + 100))
        tablet.syncOk(f.id)
        f.ruth.syncOk(f.id)
        f.dana.syncOk(f.id)
        listOf(f.ruth, tablet, f.dana).forEach { phone ->
            assertEquals("From the tablet", phone.db.storyDao().byIdIncludingDeleted("s_levee")!!.title)
        }

        // Dana is a contributor and not the creator. Her phone would not offer the edit, so it
        // is written straight into her database to ask the server itself.
        val mine = f.dana.db.storyDao().byIdIncludingDeleted("s_levee")!!
        f.dana.db.storyDao().upsert(mine.copy(title = "Dana's title", updatedAt = mine.updatedAt + 1_000))
        assertEquals(1, f.dana.syncOk(f.id).refused)
        assertEquals(
            "the family's copy replaces what was refused",
            "From the tablet", f.dana.db.storyDao().byIdIncludingDeleted("s_levee")!!.title
        )
        assertEquals("and nothing is left to refuse", 0, f.dana.syncOk(f.id).refused)
        f.ruth.syncOk(f.id)
        assertEquals("From the tablet", f.ruth.db.storyDao().byIdIncludingDeleted("s_levee")!!.title)
    }

    /**
     * The half that was cut from Gold in week 2 and built in week 4. Ruth records something,
     * and the bytes reach Dana's phone through Cloud Storage with storage.rules in the way.
     */
    @Test
    fun aRecordingReachesTheOtherPhone_bytesAndAll() = runBlocking {
        val fam = familyOfTwo()
        val spoken = "the night the levee broke".toByteArray()
        val onRuthsPhone = File(fam.ruth.fileDir, "levee.m4a").apply {
            parentFile?.mkdirs(); writeBytes(spoken)
        }

        fam.ruth.db.storyDao().upsert(story(fam.id, "s_levee", fam.ruth.uid))
        fam.ruth.db.assetDao().upsert(asset("a_levee", "s_levee", fam.id, onRuthsPhone.path))

        assertTrue(
            "Ruth could not send it",
            fam.ruth.sync.run(fam.id, fam.ruth.uid, pull = false) is SyncEngine.Result.Done
        )
        assertTrue(
            "Dana could not pull",
            fam.dana.sync.run(fam.id, fam.dana.uid, pull = true) is SyncEngine.Result.Done
        )

        val hers = fam.dana.db.assetDao().byId("a_levee")
        assertNotNull("the record did not reach Dana", hers)
        val landed = File(hers!!.localPath)
        assertTrue("the file did not reach Dana: " + hers.localPath, landed.isFile)
        assertArrayEquals("the bytes changed on the way", spoken, landed.readBytes())
        assertTrue("Dana's copy is not Ruth's file", landed.path != onRuthsPhone.path)
    }

    /**
     * A photograph saved the way the Add Document screen saves one, and checked on the other
     * phone as a picture rather than as bytes: the same file, still a JPEG that opens at its
     * size, and the story's card finds it to show.
     */
    @Test
    fun aPhotographReachesTheOtherPhone_andStillOpensAsAPicture() = runBlocking {
        val f = familyOfTwo()
        val onRuthsPhone = File(f.ruth.fileDir, "wedding.jpg").apply {
            parentFile?.mkdirs()
            val picture = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
            picture.eraseColor(Color.rgb(200, 120, 40))
            outputStream().use { picture.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        }
        val storyId = f.ruth.repo.saveDocument(
            familyId = f.id, createdByUserId = f.ruth.uid, localPath = onRuthsPhone.path,
            mimeType = "image/jpeg", title = "The wedding", subjectPersonIds = emptyList(),
            eraStart = 1961, eraEnd = null, eraPrecision = EraPrecision.EXACT, placeLabel = null,
            tags = emptyList(), visibility = Visibility.FAMILY, aiUsePolicy = AiUsePolicy.SUMMARY_OK,
            area = ArchiveArea.STORIES, now = now
        )
        val assetId = f.ruth.db.storyDao().byIdIncludingDeleted(storyId)!!.primaryAssetId!!

        f.ruth.syncOk(f.id)
        f.dana.syncOk(f.id)

        assertEquals(StoryKind.PHOTO_SET, f.dana.db.storyDao().byIdIncludingDeleted(storyId)?.kind)
        val hers = f.dana.db.assetDao().byId(assetId)
        assertNotNull("the photograph's record did not reach Dana", hers)
        assertEquals(AssetType.IMAGE, hers!!.type)
        assertEquals("image/jpeg", hers.mimeType)
        val landed = File(hers.localPath)
        assertTrue("the photograph did not reach Dana: " + hers.localPath, landed.isFile)
        assertTrue("it lost its extension: " + landed.name, landed.name.endsWith(".jpg"))
        assertArrayEquals("the bytes changed on the way", onRuthsPhone.readBytes(), landed.readBytes())
        val opened = BitmapFactory.decodeFile(landed.path)
        assertNotNull("it arrived but does not open as a picture", opened)
        assertEquals(64, opened.width)
        assertEquals(48, opened.height)
        assertEquals(
            "the story's card on Dana's phone does not find it",
            landed.path, f.dana.repo.observeImagePaths(f.id).first()[storyId]
        )
    }

    /** The same for a document, checked as a PDF that still opens on the other phone. */
    @Test
    fun aDocumentReachesTheOtherPhone_andStillOpensAsAPdf() = runBlocking {
        val f = familyOfTwo()
        val onRuthsPhone = File(f.ruth.fileDir, "deed.pdf").apply {
            parentFile?.mkdirs()
            val pdf = PdfDocument()
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(200, 200, 1).create())
            page.canvas.drawText("Deed to the farm, 1948", 10f, 20f, Paint())
            pdf.finishPage(page)
            outputStream().use { pdf.writeTo(it) }
            pdf.close()
        }
        val storyId = f.ruth.repo.saveDocument(
            familyId = f.id, createdByUserId = f.ruth.uid, localPath = onRuthsPhone.path,
            mimeType = "application/pdf", title = "The deed", subjectPersonIds = emptyList(),
            eraStart = 1948, eraEnd = null, eraPrecision = EraPrecision.EXACT, placeLabel = null,
            tags = emptyList(), visibility = Visibility.FAMILY, aiUsePolicy = AiUsePolicy.SUMMARY_OK,
            area = ArchiveArea.STORIES, now = now
        )
        val assetId = f.ruth.db.storyDao().byIdIncludingDeleted(storyId)!!.primaryAssetId!!

        f.ruth.syncOk(f.id)
        f.dana.syncOk(f.id)

        assertEquals(StoryKind.DOCUMENT, f.dana.db.storyDao().byIdIncludingDeleted(storyId)?.kind)
        val hers = f.dana.db.assetDao().byId(assetId)
        assertNotNull("the document's record did not reach Dana", hers)
        assertEquals(AssetType.DOCUMENT, hers!!.type)
        assertEquals("application/pdf", hers.mimeType)
        val landed = File(hers.localPath)
        assertTrue("the document did not reach Dana: " + hers.localPath, landed.isFile)
        assertTrue("it lost its extension: " + landed.name, landed.name.endsWith(".pdf"))
        assertArrayEquals("the bytes changed on the way", onRuthsPhone.readBytes(), landed.readBytes())
        val opened = PdfRenderer(ParcelFileDescriptor.open(landed, ParcelFileDescriptor.MODE_READ_ONLY))
        try {
            assertEquals("it arrived but does not open as a PDF", 1, opened.pageCount)
        } finally {
            opened.close()
        }
    }

    /**
     * The promise, checked where it actually has to hold. Not "the engine decided not to
     * send it" but "the server was asked and holds nothing".
     */
    @Test
    fun aPrivateRecordingIsNotOnTheServerAtAll() = runBlocking {
        val fam = familyOfTwo()
        val onRuthsPhone = File(fam.ruth.fileDir, "private.m4a").apply {
            parentFile?.mkdirs(); writeBytes("only mine".toByteArray())
        }

        fam.ruth.db.storyDao().upsert(story(fam.id, "s_private", fam.ruth.uid, visibility = Visibility.PRIVATE))
        fam.ruth.db.assetDao().upsert(asset("a_private", "s_private", fam.id, onRuthsPhone.path))
        fam.ruth.sync.run(fam.id, fam.ruth.uid, pull = false)

        // Asked from the other phone, which is where it would matter. Dana pulls the whole
        // family in the shapes the rules allow and gets neither the story nor its file.
        fam.dana.sync.run(fam.id, fam.dana.uid, pull = true)
        assertNull("it reached the other phone", fam.dana.db.assetDao().byId("a_private"))
        assertNull("its story reached the other phone", fam.dana.db.storyDao().byIdIncludingDeleted("s_private"))
    }

    /**
     * The live project until it is on the paid plan: Firestore answers and Cloud Storage does
     * not. Ruth holds a recording she cannot upload, and her sync still sends the family tree
     * and still brings down what Dana added. The recording's record goes too, so Dana's phone
     * knows it exists and fetches the bytes once they can travel.
     */
    @Test
    fun aPhoneWithoutStorageStillSendsTheTreeAndStillPulls() = runBlocking {
        val f = familyOfTwo(ruthHasStorage = false)
        val onRuthsPhone = File(f.ruth.fileDir, "levee.m4a").apply {
            parentFile?.mkdirs(); writeBytes("the night the levee broke".toByteArray())
        }
        f.ruth.db.storyDao().upsert(story(f.id, "s_levee", f.ruth.uid))
        f.ruth.db.assetDao().upsert(asset("a_levee", "s_levee", f.id, onRuthsPhone.path))
        val walt = f.ruth.repo.addPerson(f.id, "Walt Delaney", nowMillis = now)

        f.dana.db.storyDao().upsert(story(f.id, "s_dana", f.dana.uid))
        f.dana.syncOk(f.id)

        f.ruth.syncOk(f.id)
        assertNotNull("Dana's story did not reach Ruth", f.ruth.db.storyDao().byIdIncludingDeleted("s_dana"))
        assertEquals(
            "the bytes wait instead of being given up on",
            UploadState.UPLOADING, f.ruth.db.assetDao().byId("a_levee")!!.uploadState
        )

        f.dana.syncOk(f.id)
        assertNotNull("the tree did not reach Dana", f.dana.db.personDao().byId(walt))
        assertNotNull("the record did not reach Dana", f.dana.db.assetDao().byId("a_levee"))
    }

    /**
     * Private has to mean private after the fact too. Ruth shares a recording, Dana's phone
     * downloads it, then Ruth takes the story back. The bytes and the record leave the server,
     * Dana's copy leaves her phone, and Ruth's own recording stays where it was made.
     */
    @Test
    fun aRecordingTakenBackToPrivateLeavesTheServerAndTheOtherPhone() = runBlocking {
        val f = familyOfTwo()
        val onRuthsPhone = recordingOn(f.ruth)
        val remotePath = sharedRecording(f, onRuthsPhone)
        val danasCopy = File(f.dana.db.assetDao().byId("a_levee")!!.localPath)
        assertTrue("Dana needs a copy for this test to mean anything", danasCopy.isFile)
        assertEquals(1, objectsOnServer(f.ruth, remotePath))

        assertTrue(f.ruth.changeWhoMaySee(f.id, "s_levee", Visibility.PRIVATE, now + 1000))
        f.ruth.syncOk(f.id)

        assertEquals("the bytes are still on the server", 0, objectsOnServer(f.ruth, remotePath))
        assertTrue("Ruth's own recording was deleted", onRuthsPhone.isFile)

        f.dana.syncOk(f.id)
        assertNull("the story is still on Dana's phone", f.dana.db.storyDao().byIdIncludingDeleted("s_levee"))
        assertNull("the file's record is still on Dana's phone", f.dana.db.assetDao().byId("a_levee"))
        assertTrue("Dana's copy of the recording is still on her phone", !danasCopy.exists())
    }

    /**
     * The same promise when a story narrows instead of going private. The file stays on the
     * server for the people still allowed, its record carries the new answer, and Dana, who is
     * no longer one of them, can neither read the bytes nor keep her copy.
     */
    @Test
    fun aStoryThatNarrowsWhoMaySeeItNarrowsItsFileToo() = runBlocking {
        val f = familyOfTwo()
        val remotePath = sharedRecording(f, recordingOn(f.ruth))
        val danasCopy = File(f.dana.db.assetDao().byId("a_levee")!!.localPath)
        assertTrue("Dana needs a copy for this test to mean anything", danasCopy.isFile)

        assertTrue(f.ruth.changeWhoMaySee(f.id, "s_levee", Visibility.SELECTED, now + 1000))
        f.ruth.syncOk(f.id)

        assertEquals("the file left the server for everyone", 1, objectsOnServer(f.ruth, remotePath))
        val read = runCatching { f.dana.storage.reference.child(remotePath).getBytes(1024 * 1024).awaitTask() }
        assertTrue("Dana can still read the bytes", read.isFailure)

        f.dana.syncOk(f.id)
        assertNull("the story is still on Dana's phone", f.dana.db.storyDao().byIdIncludingDeleted("s_levee"))
        assertTrue("Dana's copy of the recording is still on her phone", !danasCopy.exists())
    }

    /** Erasing, after the thirty days or from Recently deleted, takes the file off the server too. */
    @Test
    fun anErasedRecordingLeavesTheServer() = runBlocking {
        val f = familyOfTwo()
        val remotePath = sharedRecording(f, recordingOn(f.ruth))
        val ruthAsOwner = f.ruth.viewer(f.id, MemberRole.OWNER)

        assertTrue(f.ruth.repo.deleteStory("s_levee", ruthAsOwner, now + 1000))
        f.ruth.syncOk(f.id)
        assertEquals("a deleted story's file should wait out the thirty days", 1, objectsOnServer(f.ruth, remotePath))

        assertTrue(f.ruth.repo.eraseStory("s_levee", ruthAsOwner, now + 2000))
        f.ruth.syncOk(f.id)
        assertEquals("the erased story's file is still on the server", 0, objectsOnServer(f.ruth, remotePath))
    }

    @Test
    fun anAccountOutsideTheFamilyGetsNothingAndChangesNothing() = runBlocking {
        val f = familyOfTwo()
        f.ruth.db.storyDao().upsert(story(f.id, "s_levee", f.ruth.uid))
        f.ruth.syncOk(f.id)

        val stranger = phone("stranger").signUp(email("stranger"))
        assertEquals(SyncEngine.Result.NotAMember, stranger.sync.run(f.id, stranger.uid, pull = true))
        assertTrue(stranger.db.storyDao().all(f.id).isEmpty())
    }

    private companion object {
        const val AUTH_PORT = 9099
        const val FIRESTORE_PORT = 8080
        const val STORAGE_PORT = 9199
        const val PASSWORD = "not a real password, only an emulator account"
    }
}

private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
