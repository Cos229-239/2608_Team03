package com.arv.app.core.data

import com.arv.app.core.ai.MemoryAccess
import com.arv.app.core.ai.Viewer
import com.arv.app.core.model.Person
import com.arv.app.core.model.Story

/**
 * Whether a person's circle draws a face or their initials, and who may put one there.
 *
 * Pure, so both questions can be tested exhaustively without a database or a device.
 *
 * The first version of this resolved a portrait through the story its photograph belonged
 * to, and checked the permission filter on every render. That was the wrong shape. A face
 * is identification rather than testimony, like a name, and a person's name is not gated
 * per screen. What actually needs guarding is narrower and earlier: choosing a photograph
 * out of the archive must not be a way to promote something the chooser was never allowed
 * to see. That check is [mayTakeFromArchive], and it happens once, at the moment somebody
 * decides, which is how every other consent decision in this app works.
 */
object Portrait {

    sealed interface Result {
        /** Draw this file. */
        data class Show(val localPath: String) : Result

        /** Nobody has given this person a face. Draw initials. */
        data object NotSet : Result

        /**
         * There is a path and the file is gone. Draw initials.
         *
         * Kept distinct from [NotSet] so a vanished file reads as something repairable
         * rather than as a choice nobody made.
         */
        data object Missing : Result
    }

    /**
     * @param fileExists injected so this stays testable without touching a disk.
     */
    fun resolve(portraitPath: String?, fileExists: (String) -> Boolean): Result = when {
        portraitPath == null -> Result.NotSet
        !fileExists(portraitPath) -> Result.Missing
        else -> Result.Show(portraitPath)
    }

    /**
     * May this viewer make this archive photograph into somebody's face?
     *
     * The whole privacy question, in one place. A portrait is shown to everyone in the
     * family on the feed, the people list and a profile header, so choosing a private
     * photograph as one would publish it to all three. Refusing here is what stops the
     * circle becoming a laundry for material the permission filter withheld.
     *
     * Deliberately the same [MemoryAccess.canRead] every screen uses, consent included,
     * rather than a rule of its own. A second definition of "may see" is a second thing to
     * get wrong.
     */
    fun mayTakeFromArchive(story: Story?, viewer: Viewer, people: List<Person>): Boolean =
        story != null && MemoryAccess.canRead(story, viewer, people)

    /** The circle's fallback. Two initials, and never more, however many names somebody has. */
    fun initialsOf(displayName: String): String =
        displayName.split(" ")
            .mapNotNull { it.firstOrNull()?.uppercase() }
            .take(2)
            .joinToString("")
}
