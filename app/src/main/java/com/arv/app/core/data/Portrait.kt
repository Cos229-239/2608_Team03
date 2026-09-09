package com.arv.app.core.data

import com.arv.app.core.ai.MemoryAccess
import com.arv.app.core.ai.Viewer
import com.arv.app.core.data.local.AssetEntity
import com.arv.app.core.model.Person
import com.arv.app.core.model.Story

/**
 * Whether a person's face can be drawn for the person looking at the screen.
 *
 * Pure so it can be tested exhaustively without a database or a device, same as
 * [Invitation] and [com.arv.app.core.ai.MemoryAccess].
 *
 * This exists because a portrait is a permission decision wearing a decoration's clothes.
 * An avatar appears on the feed, on the people list, and at the top of a profile, so a
 * portrait pointing at a photograph somebody marked private would leak it onto three
 * screens at once, in a circle nobody thinks to check. The exact hole that [MemoryAccess]
 * closed for the edit path, reopened by a nice touch.
 *
 * So the rule is the one the rest of the app already uses: the photograph is drawn only if
 * the story it belongs to is readable by this viewer, consent included. Otherwise the
 * initials stand in, and the initials are a real design rather than a placeholder, which
 * is what makes failing closed cheap here.
 */
object Portrait {

    sealed interface Result {
        /** Draw this file. */
        data class Show(val localPath: String) : Result

        /** Nobody has chosen a face. Draw initials. */
        data object NotSet : Result

        /**
         * A face was chosen and this viewer may not see it. Draw initials.
         *
         * Deliberately not surfaced differently from [NotSet] in the UI. "There is a
         * photograph of your grandmother here that you are not allowed to see" is itself a
         * disclosure, and on a private photograph the person who filed it did not agree to
         * make it either.
         */
        data object Withheld : Result

        /**
         * The pointer no longer resolves: the story was deleted, the asset row went with
         * it, or the file is gone from disk. Draw initials.
         *
         * Distinct from [Withheld] because this one is a repair the app could offer, and
         * because silently treating a deletion as a permission failure would hide a bug.
         */
        data object Missing : Result
    }

    /**
     * @param portraitAssetId from [Person.portraitAssetId].
     * @param asset the row for that id, or null if it is gone.
     * @param story the story the asset belongs to, or null if it is gone.
     * @param people every person in the archive, for the consent check on [story].
     * @param fileExists injected so this stays testable without touching a disk.
     */
    fun resolve(
        portraitAssetId: String?,
        asset: AssetEntity?,
        story: Story?,
        viewer: Viewer,
        people: List<Person>,
        fileExists: (String) -> Boolean
    ): Result {
        if (portraitAssetId == null) return Result.NotSet
        if (asset == null || story == null) return Result.Missing

        // Read the permission before the disk. A private photograph that happens to be
        // missing should still answer "not yours", so that fixing the file never turns
        // into a way of finding out what was in it.
        if (!MemoryAccess.canRead(story, viewer, people)) return Result.Withheld

        if (!fileExists(asset.localPath)) return Result.Missing
        return Result.Show(asset.localPath)
    }

    /** The circle's fallback. Two initials, and never more, however many names somebody has. */
    fun initialsOf(displayName: String): String =
        displayName.split(" ")
            .mapNotNull { it.firstOrNull()?.uppercase() }
            .take(2)
            .joinToString("")
}
