package com.arv.app.core.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arv.app.core.data.local.ArvDatabase
import com.arv.app.core.data.local.MemberEntity
import com.arv.app.core.model.MemberRole
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Signing out and back in finds the archive again.
 *
 * From the weekly tester report of 13 September: after a sign-out, signing in with the same
 * account asked for a family name and offered no way back to the archive that was still on
 * the phone. The archive was never lost, only its name and any list that could offer it.
 *
 * An in-memory database, so nothing here touches the archive on the device running it.
 */
@RunWith(AndroidJUnit4::class)
class ArchiveRecoveryTest {

    private val db = Room.inMemoryDatabaseBuilder(
        InstrumentationRegistry.getInstrumentation().targetContext,
        ArvDatabase::class.java
    ).build()
    private val repo = StoryRepository(db)

    @After
    fun close() = db.close()

    @Test
    fun theArchiveAnAccountCreatedIsOfferedBackWithItsName() = runBlocking {
        val made = repo.createFamily("Reinhold", "Angela", nowMillis = 1_000L, userId = "u_angela")
        repo.addPerson(made.familyId, "Ann", nowMillis = 1_001L)

        val offered = repo.archivesFor("u_angela")

        assertEquals(1, offered.size)
        val archive = offered.single()
        assertEquals(made.familyId, archive.familyId)
        assertEquals("Reinhold", archive.name)
        assertEquals(MemberRole.OWNER, archive.role)
        assertEquals(listOf("Angela", "Ann"), archive.somePeople)
    }

    @Test
    fun anotherAccountIsNotOfferedSomebodyElsesArchive() = runBlocking {
        repo.createFamily("Reinhold", "Angela", nowMillis = 1_000L, userId = "u_angela")

        assertTrue(repo.archivesFor("u_stranger").isEmpty())
    }

    @Test
    fun aJoinedArchiveIsOfferedTooNewestFirst() = runBlocking {
        val mine = repo.createFamily("Reinhold", "Angela", nowMillis = 1_000L, userId = "u_angela")
        repo.admitMember(
            MemberEntity(familyId = "fam_delaney", userId = "u_angela", role = MemberRole.CONTRIBUTOR, joinedAt = 5_000L, invitedBy = "u_ruth")
        )
        repo.rememberFamily("fam_delaney", "Delaney", nowMillis = 5_000L)

        val offered = repo.archivesFor("u_angela")

        assertEquals(listOf("fam_delaney", mine.familyId), offered.map { it.familyId })
        assertEquals("Delaney", offered.first().name)
        assertEquals(MemberRole.CONTRIBUTOR, offered.first().role)
    }

    @Test
    fun anArchiveFromBeforeNamesWereKeptIsStillOfferedAndLearnsItsName() = runBlocking {
        repo.admitMember(MemberEntity(familyId = "fam_old", userId = "u_angela", role = MemberRole.OWNER, joinedAt = 10L))

        assertNull(repo.archivesFor("u_angela").single().name)

        repo.rememberFamily("fam_old", "  ", nowMillis = 20L)
        assertNull("a blank name is not a name", repo.archivesFor("u_angela").single().name)

        repo.rememberFamily("fam_old", "Reinhold", nowMillis = 30L)
        assertEquals("Reinhold", repo.archivesFor("u_angela").single().name)
    }
}
