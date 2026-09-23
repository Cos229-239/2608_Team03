package com.arv.app.core.sync

import com.arv.app.core.data.local.StoryEntity
import com.arv.app.core.model.ArchiveArea
import com.arv.app.core.model.Visibility

/**
 * What may leave this phone, and when a row has something to say.
 *
 * Pure on purpose, like [com.arv.app.core.ai.MemoryAccess]: the decision that matters most in
 * sync is whether a memory goes to the server at all, and that should be testable without a
 * device, a database, or a network.
 */
object SyncPolicy {

    /**
     * Whether the family's server may hold this story.
     *
     * Private stays on the phone that made it, and so does every health record, whatever its
     * visibility says. The rules on the server would keep a private story from other readers
     * anyway. Not sending it means there is nothing on the server to keep from them.
     */
    fun shares(story: StoryEntity): Boolean =
        story.visibility != Visibility.PRIVATE && story.area != ArchiveArea.HEALTH

    /** True when a row carries an edit the server has neither confirmed nor refused. */
    fun hasNews(updatedAt: Long, syncedAt: Long?, refusedAt: Long?): Boolean =
        updatedAt != syncedAt && updatedAt != refusedAt

    enum class StoryAction {
        /** Put this version on the server. */
        Send,
        /** The server holds a copy it may no longer have: the story went private or health. */
        Withdraw,
        Nothing
    }

    fun decide(story: StoryEntity): StoryAction = when {
        !shares(story) ->
            if (story.syncedAt != null) StoryAction.Withdraw else StoryAction.Nothing
        !hasNews(story.updatedAt, story.syncedAt, story.refusedAt) -> StoryAction.Nothing
        // Deleted before it was ever shared. The family never had it, so there is nothing to
        // hide from them; it goes if somebody brings it back.
        story.deletedAt != null && story.syncedAt == null -> StoryAction.Nothing
        else -> StoryAction.Send
    }

    /**
     * How long a deleted story waits in Recently deleted before it is erased for good.
     *
     * A delete that never erases is a promise the app cannot keep: the recording stays on the
     * phone, and on the server if it was shared, for as long as the archive lives. Thirty days
     * is what a phone's own trash gives somebody to change their mind.
     */
    const val ERASE_AFTER_MS = 30L * 24 * 60 * 60 * 1000

    /** True once a deleted story has waited out [ERASE_AFTER_MS]. */
    /**
     * Whether a recording or photograph may leave the phone.
     *
     * It asks the story, and only the story. A file has no audience of its own, so there is
     * no second place for the answer to be different, and a file whose story was never
     * shareable has never been offered to the server in the first place.
     */
    fun sharesFile(story: StoryEntity): Boolean = shares(story) && story.deletedAt == null

    fun dueToErase(deletedAt: Long?, now: Long): Boolean =
        deletedAt != null && now - deletedAt >= ERASE_AFTER_MS

    /**
     * The updatedAt for an edit: now, or one past the version it replaces when this phone's
     * clock is behind the phone that wrote that version.
     *
     * The later edit wins, and later is read off these numbers. A phone whose clock runs slow
     * would otherwise stamp its edit older than the one it just replaced, lose to it on the
     * server, and watch its own change disappear on the next pull.
     */
    fun stamp(previous: Long, now: Long): Long = maxOf(now, previous + 1)
}
