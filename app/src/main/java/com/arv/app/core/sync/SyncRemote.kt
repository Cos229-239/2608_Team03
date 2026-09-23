package com.arv.app.core.sync

import com.arv.app.core.data.local.AssetEntity
import com.arv.app.core.data.local.MemberEntity
import com.arv.app.core.data.local.PersonEntity
import com.arv.app.core.data.local.RelationshipEntity
import com.arv.app.core.data.local.StoryEntity
import java.io.File

/**
 * The family's server, as sync sees it. Behind an interface so everything above it is tested
 * with a fake and the app builds without Firebase, the same arrangement as
 * [com.arv.app.core.remote.InviteRemote].
 *
 * What can cross is exactly what these methods carry: stories, people, the links between
 * them, the member list the invitation code already keeps there, and the recordings and
 * photographs belonging to stories that may travel. A story only reaches [sendStory] through
 * [SyncEngine], which asks [SyncPolicy.shares] first, so a private story or a health record
 * never does, and a file only reaches [uploadAssetFile] after [SyncPolicy.sharesFile] says
 * the same about the story it belongs to.
 */
interface SyncRemote {

    /** False on a build with no Firebase configuration. Nothing is attempted. */
    val available: Boolean

    /**
     * Puts this version of a story on the server unless the server holds a later one.
     *
     * [neverSent] is true when this phone has no record of the server ever holding it. The
     * later-edit check has to read the server's copy first, and firestore.rules only lets a
     * story be read when its fields say who may read it, so a story that is not there yet
     * cannot be read to find that out. A story this phone made and never sent has no other
     * copy anywhere, so it is written straight.
     */
    suspend fun sendStory(story: StoryEntity, neverSent: Boolean): Sent

    /** Takes a story off the server: it went private or became a health record. */
    suspend fun withdrawStory(familyId: String, storyId: String): Sent

    suspend fun sendPerson(person: PersonEntity): Sent

    suspend fun sendRelationship(edge: RelationshipEntity): Sent

    suspend fun removeRelationship(familyId: String, edgeId: String): Sent

    /**
     * Puts the record of a recording or photograph on the server. It has to land before the
     * bytes do: storage.rules decides who may write the file by reading this document, so
     * uploading first is asking permission from something that is not there yet.
     */
    suspend fun sendAsset(asset: AssetEntity, story: StoryEntity): Sent

    /** The bytes themselves, to the path the record already names. */
    suspend fun uploadAssetFile(remotePath: String, file: File): Sent

    /** Brings a file down to [into]. The caller keeps nothing on a failure. */
    suspend fun downloadAssetFile(remotePath: String, into: File): Sent

    /** Takes a file off the server when its story is withdrawn or erased. */
    suspend fun removeAssetFile(remotePath: String): Sent

    /**
     * Everything in the family this account may read, asked for in the shapes the rules can
     * prove, all of it or nothing.
     */
    suspend fun fetch(familyId: String, userId: String): Fetched

    /** The remote for a build that has none. */
    object None : SyncRemote {
        override val available = false
        override suspend fun sendStory(story: StoryEntity, neverSent: Boolean) = Sent.Unreachable
        override suspend fun withdrawStory(familyId: String, storyId: String) = Sent.Unreachable
        override suspend fun sendPerson(person: PersonEntity) = Sent.Unreachable
        override suspend fun sendRelationship(edge: RelationshipEntity) = Sent.Unreachable
        override suspend fun removeRelationship(familyId: String, edgeId: String) = Sent.Unreachable
        override suspend fun sendAsset(asset: AssetEntity, story: StoryEntity) = Sent.Unreachable
        override suspend fun uploadAssetFile(remotePath: String, file: File) = Sent.Unreachable
        override suspend fun downloadAssetFile(remotePath: String, into: File) = Sent.Unreachable
        override suspend fun removeAssetFile(remotePath: String) = Sent.Unreachable
        override suspend fun fetch(familyId: String, userId: String): Fetched = Fetched.Unreachable
    }
}

enum class Sent {
    /** The server holds it now. */
    Done,
    /** The server already holds a later edit. The next pull brings that one down. */
    Stale,
    /** The rules said no. Asking again with the same row gets the same answer. */
    Refused,
    /** No answer: no connection, or a server that did not respond. Try again later. */
    Unreachable
}

sealed interface Fetched {

    /**
     * The family as the server holds it for this account.
     *
     * The id sets include documents this version of the app could not read. Those are left
     * alone, but they are still there, and a phone deciding what has gone from the server must
     * not mistake "could not read" for "gone".
     */
    data class Got(
        val members: List<MemberEntity>,
        val memberIds: Set<String>,
        val people: List<PersonEntity>,
        val relationships: List<RelationshipEntity>,
        val relationshipIds: Set<String>,
        val stories: List<StoryEntity>,
        val storyIds: Set<String>,
        /** Records of files, in the same permission shapes the stories came in. */
        val assets: List<AssetEntity> = emptyList(),
        val assetIds: Set<String> = emptySet()
    ) : Fetched

    /** The server does not count this account as in the family: never registered, or removed. */
    data object NotAMember : Fetched

    /** In the family, but one of the reads was refused. Nothing is concluded from a partial answer. */
    data object Refused : Fetched

    data object Unreachable : Fetched
}
