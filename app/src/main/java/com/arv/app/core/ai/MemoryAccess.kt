package com.arv.app.core.ai

import com.arv.app.core.model.AiUsePolicy
import com.arv.app.core.model.ArchiveArea
import com.arv.app.core.model.LibrarianScope
import com.arv.app.core.model.MemberRole
import com.arv.app.core.model.Person
import com.arv.app.core.model.Story
import com.arv.app.core.model.Visibility

/**
 * Who is asking, and what the family already knows about them.
 *
 * [branchRootPersonId] is the ancestor that defines "my branch". Two cousins on different
 * sides of a family reach different ancestors, which is the whole point of BRANCH.
 */
data class Viewer(
    val userId: String,
    val role: MemberRole,
    /** The archive this viewer is standing in. Nothing outside it is theirs to read. */
    val familyId: String = "",
    /**
     * Everyone this viewer descends from, plus the person they are, from
     * [com.arv.app.core.ai.Lineage.ancestorsOf].
     *
     * A set, not one id, because a person is never in a single branch. You are in your
     * father's line and your mother's line and your grandmother's at once. The previous
     * shape was a single branchRootPersonId, which could not express that and made the
     * whole BRANCH feature unimplementable.
     */
    val ancestorIds: Set<String> = emptySet(),
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
        // The family boundary, checked before anything else.
        //
        // Every query in the app filters by familyId already, so this is belt and braces
        // today. It stops being belt and braces the moment two families can touch: sharing
        // an ancestor with a cousin must never hand them another family's memories, and a
        // convention that every caller filters first is not something to bet an archive on.
        if (story.familyId != viewer.familyId) return false

        val keeper = viewer.role == MemberRole.OWNER || viewer.role == MemberRole.KEEPER

        // Restricted material is keeper-only regardless of visibility. Ceremonial and
        // sensitive recordings are gated by role, not by who happens to be in the family.
        if (story.restricted && !keeper) return false

        return when (story.visibility) {
            Visibility.FAMILY -> true
            // Readable by anyone descended from the ancestor the branch is named after.
            // A story with no branch root named is unreadable rather than public: an
            // unanswered "which side of the family" must fail closed.
            Visibility.BRANCH -> story.branchRootPersonId != null &&
                story.branchRootPersonId in viewer.ancestorIds
            Visibility.SELECTED -> viewer.userId in story.sharedWithUserIds ||
                story.createdBy == viewer.userId
            // The one case a keeper does NOT get: private stays private. Being a keeper,
            // or a memory steward, never grants read access to what someone kept to
            // themselves. If this ever becomes `|| keeper`, the product is broken.
            Visibility.PRIVATE -> story.createdBy == viewer.userId
        }
    }

    /**
     * Does a missing consent record stand between this viewer and this memory?
     *
     * Consent follows the voice: a story is blocked while any of its narrators has no
     * consent decision on file. The person page has promised exactly this in writing
     * ("their memories stay restricted until one exists") since the flag was added, and
     * until now nothing enforced it, so the sentence was a bluff.
     *
     * Who still reads a blocked story: its creator, who holds the recording and the
     * responsibility of getting the consent, and the narrator themselves or their memory
     * steward. Deliberately not keepers: a role in the app is not a substitute for a
     * person's answer, the same stance PRIVATE takes.
     */
    fun consentBlocks(story: Story, people: List<Person>, viewer: Viewer): Boolean {
        val undecided = people.filter {
            it.personId in story.narratorIds && it.needsAConsentDecision
        }
        if (undecided.isEmpty()) return false
        if (story.createdBy == viewer.userId) return false
        return !undecided.all { it.personId in viewer.personIds }
    }

    /**
     * [canRead] with consent enforced. Every surface that can show a story's content
     * calls this form; the two-argument form is the visibility gate alone.
     */
    fun canRead(story: Story, viewer: Viewer, people: List<Person>): Boolean =
        canRead(story, viewer) && !consentBlocks(story, people, viewer)

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
        // The family boundary, before anything else, exactly as [canRead] checks it.
        // Reading somebody else's archive was closed off; editing and deleting it was
        // left open, which is the more dangerous half of the same hole.
        if (story.familyId != viewer.familyId) return false

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
        scope: LibrarianScope,
        people: List<Person> = emptyList()
    ): Partitioned {
        // Consent gates the model exactly as it gates a screen. A voice nobody agreed
        // to share is not summary material either.
        val usable = candidates.filter {
            canLibrarianUse(it, viewer, scope) && !consentBlocks(it, people, viewer)
        }
        return Partitioned(usable = usable, withheldCount = candidates.size - usable.size)
    }

    data class Partitioned(val usable: List<Story>, val withheldCount: Int)
}


