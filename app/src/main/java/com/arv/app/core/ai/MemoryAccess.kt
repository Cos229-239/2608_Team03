package com.arv.app.core.ai

import com.arv.app.core.model.AiUsePolicy
import com.arv.app.core.model.ArchiveArea
import com.arv.app.core.model.LibrarianScope
import com.arv.app.core.model.MemberRole
import com.arv.app.core.model.Story
import com.arv.app.core.model.Visibility

/**
 * Who is asking, and what the family already knows about them.
 *
 * [branchRootPersonId] is the ancestor that defines "my branch". Two cousins on different
 * sides of a family have different branch roots, which is the whole point of BRANCH.
 */
data class Viewer(
    val userId: String,
    val role: MemberRole,
    val branchRootPersonId: String?,
    /**
     * The person this user IS, plus anyone they are memory steward for. Used to decide
     * control of health records, which follow their subject rather than their author.
     */
    val personIds: Set<String> = emptySet()
)

/**
 * The permission filter. Pure functions, no Android, no Firebase, no coroutines, so it can
 * be unit tested exhaustively without a device.
 *
 * This is the most safety-critical code in the app. A bug here is not a defect, it is a
 * family member reading something that was never meant for them. Firestore rules enforce
 * the same logic server-side; this copy exists so the client never renders what the server
 * would refuse, and so the librarian can filter before it ever builds a prompt.
 *
 * See docs/SPEC.md §4 and §5.
 */
object MemoryAccess {

    /** Can this viewer open this memory at all? */
    fun canRead(story: Story, viewer: Viewer): Boolean {
        val keeper = viewer.role == MemberRole.OWNER || viewer.role == MemberRole.KEEPER

        // Restricted material is keeper-only regardless of visibility. Ceremonial and
        // sensitive recordings are gated by role, not by who happens to be in the family.
        if (story.restricted && !keeper) return false

        return when (story.visibility) {
            Visibility.FAMILY -> true
            Visibility.BRANCH -> viewer.branchRootPersonId != null &&
                viewer.branchRootPersonId == story.branchRootPersonIdOrNull()
            Visibility.SELECTED -> viewer.userId in story.sharedWithUserIds ||
                story.createdBy == viewer.userId
            // The one case a keeper does NOT get: private stays private. Being a keeper,
            // or a memory steward, never grants read access to what someone kept to
            // themselves. If this ever becomes `|| keeper`, the product is broken.
            Visibility.PRIVATE -> story.createdBy == viewer.userId
        }
    }

    /**
     * Can a librarian working in [scope] use this memory when composing an answer?
     *
     * Two independent gates. Readability is not permission to summarize: a memory can be
     * visible to the whole family and still be off limits to the model.
     */
    fun canLibrarianUse(story: Story, viewer: Viewer, scope: LibrarianScope): Boolean {
        if (story.aiUsePolicy == AiUsePolicy.NONE) return false

        return when (scope) {
            // The personal vault reads everything this user owns, plus whatever the
            // family scope would allow them anyway.
            LibrarianScope.PERSONAL -> story.createdBy == viewer.userId || canRead(story, viewer)
            // The family scope never reaches into anyone's private material, including
            // the asker's own. Asking "our family librarian" is asking the shared library.
            LibrarianScope.FAMILY -> story.visibility != Visibility.PRIVATE && canRead(story, viewer)
        }
    }

    /**
     * Health records answer to their subject, not their author.
     *
     * A cousin can write down that your mother had a heart condition, but that record
     * belongs to your mother. She, or the steward she named, decides whether it stays.
     * Without this, one relative could publish another's medical history to forty people
     * and the subject would have no standing to remove it.
     */
    fun canEdit(story: Story, viewer: Viewer): Boolean {
        if (story.area == ArchiveArea.HEALTH) {
            val isSubject = story.subjectPersonIds.any { it in viewer.personIds }
            return isSubject || story.createdBy == viewer.userId
        }
        return story.createdBy == viewer.userId ||
            viewer.role == MemberRole.OWNER ||
            viewer.role == MemberRole.KEEPER
    }

    /**
     * May the answer paraphrase this memory, or must it quote verbatim?
     *
     * Health records are never paraphrasable, whatever their policy says. Paraphrasing a
     * medical record is how "three relatives mentioned heart trouble" becomes "your family
     * has heart disease", and the second sentence is a diagnosis nobody was qualified to
     * make. The records get shown; the reading of them belongs to a doctor.
     */
    fun mayParaphrase(story: Story): Boolean =
        story.area != ArchiveArea.HEALTH && story.aiUsePolicy == AiUsePolicy.SUMMARY_OK

    /**
     * Split a candidate set into what the librarian may use and how many it had to drop.
     *
     * The count is returned so the UI can be honest that something was withheld. It never
     * returns the withheld stories themselves, so there is no path by which a title, a
     * narrator, or a snippet of private material can leak into a "we found more" message.
     */
    fun partition(
        candidates: List<Story>,
        viewer: Viewer,
        scope: LibrarianScope
    ): Partitioned {
        val usable = candidates.filter { canLibrarianUse(it, viewer, scope) }
        return Partitioned(usable = usable, withheldCount = candidates.size - usable.size)
    }

    data class Partitioned(val usable: List<Story>, val withheldCount: Int)
}

/**
 * Branch scoping is stored on the story in Firestore but is not yet on the local entity.
 * Until DAT-7 lands it, BRANCH behaves as the strictest reasonable thing: nobody matches.
 * Failing closed is the only acceptable default for a permission check.
 */
private fun Story.branchRootPersonIdOrNull(): String? = null
