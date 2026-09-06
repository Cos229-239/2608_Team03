package com.arv.app.core.session

import android.content.Context
import android.content.SharedPreferences
import com.arv.app.core.model.MemberRole

/**
 * Who is using the app, and whose archive they are standing in.
 *
 * Read synchronously and stored in SharedPreferences rather than Room on purpose: the
 * answer is needed before the first frame, to decide whether onboarding runs at all.
 * A suspending read would mean rendering the family feed before knowing which family.
 *
 * Two questions live here and they are deliberately not the same question:
 *
 *  - [isAuthenticated]: does Firebase know who this is. Set by the auth screen.
 *  - [isSignedIn]: is an archive open. Set by onboarding when a family is created or joined.
 *
 * Conflating them was the trap. A person can be authenticated with no family yet (fresh
 * account), and the sample family can be open with nobody authenticated at all (the build
 * review path, kept on purpose). Routing reads both and never infers one from the other.
 *
 * [userId] is the Firebase uid once an account exists. The sample family still uses its
 * fixed demo id, which is how the two never mix.
 */
object ActiveSession {

    private const val PREFS = "arv.session"
    private const val KEY_FAMILY = "familyId"
    private const val KEY_USER = "userId"
    private const val KEY_FAMILY_NAME = "familyName"
    private const val KEY_AUTH_UID = "authUid"
    private const val KEY_AUTH_EMAIL = "authEmail"
    private const val KEY_ROLE = "role"

    private var prefs: SharedPreferences? = null

    @Volatile
    var familyId: String? = null
        private set

    @Volatile
    var userId: String? = null
        private set

    /** Shown in the app bar so it is always obvious whose archive is open. */
    @Volatile
    var familyName: String? = null
        private set

    /**
     * What this account may do in the open archive, from its member row.
     *
     * Persisted alongside the family rather than derived like [ancestorIds], because every
     * screen's ViewModel captures the viewer at construction and the first ones are built
     * before any database read completes. A role that arrived late would be a role the
     * feed never saw. [com.arv.app.core.data.StoryRepository.refreshLineage] keeps it true
     * to the row; sign-in paths set it from what they just created or opened.
     */
    @Volatile
    var role: MemberRole? = null
        private set

    fun setRole(role: MemberRole) {
        this.role = role
        prefs?.edit()?.putString(KEY_ROLE, role.name)?.apply()
    }

    /** Firebase Auth's uid for the account that is signed in, or null when nobody is. */
    @Volatile
    var authUid: String? = null
        private set

    /** Shown in settings so the person can see which account they are signed in as. */
    @Volatile
    var authEmail: String? = null
        private set

    /**
     * Everyone the signed-in person descends from, plus themselves.
     *
     * Held in memory rather than persisted because it is derived: the family graph is the
     * truth and this is a cache of one walk over it, recomputed on launch and whenever the
     * tree changes. Empty until that walk runs, which makes BRANCH visibility fail closed
     * during startup rather than briefly showing one side of a family to the other.
     */
    @Volatile
    var ancestorIds: Set<String> = emptySet()
        private set

    fun setLineage(ids: Set<String>) { ancestorIds = ids }

    /**
     * The person this user IS, plus anyone they are memory steward for.
     *
     * Was always empty, which made the rule that health records follow their subject
     * unreachable outside unit tests.
     */
    @Volatile
    var personIds: Set<String> = emptySet()
        private set

    fun setPersonIds(ids: Set<String>) { personIds = ids }

    /** An archive is open. This alone decides whether the family shell renders. */
    val isSignedIn: Boolean get() = familyId != null

    /** An account exists. This alone decides whether the auth screen is skipped. */
    val isAuthenticated: Boolean get() = authUid != null

    fun restore(context: Context) {
        val p = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        familyId = p.getString(KEY_FAMILY, null)
        userId = p.getString(KEY_USER, null)
        familyName = p.getString(KEY_FAMILY_NAME, null)
        authUid = p.getString(KEY_AUTH_UID, null)
        authEmail = p.getString(KEY_AUTH_EMAIL, null)
        role = p.getString(KEY_ROLE, null)?.let { runCatching { MemberRole.valueOf(it) }.getOrNull() }

        // A session saved before roles existed has a family open and no role stored. The
        // only way into a family back then was to create it, so this person owns it, and
        // the member row written on the next refresh says the same. Joining always stores
        // a role at the moment of joining, so a missing one never means "joined".
        if (familyId != null && role == null) setRole(MemberRole.OWNER)
    }

    /** Called by the auth screen once Firebase has confirmed who this is. */
    fun setAuth(uid: String, email: String?) {
        authUid = uid
        authEmail = email
        prefs?.edit()
            ?.putString(KEY_AUTH_UID, uid)
            ?.putString(KEY_AUTH_EMAIL, email)
            ?.apply()
    }

    /**
     * Opens an archive. The role is required, not defaulted, because every caller knows
     * it: it just created the family, joined one, or opened the sample.
     */
    fun set(familyId: String, userId: String, familyName: String, role: MemberRole) {
        this.familyId = familyId
        this.userId = userId
        this.familyName = familyName
        this.role = role
        prefs?.edit()
            ?.putString(KEY_FAMILY, familyId)
            ?.putString(KEY_USER, userId)
            ?.putString(KEY_FAMILY_NAME, familyName)
            ?.putString(KEY_ROLE, role.name)
            ?.apply()
    }

    /**
     * Signs out of the archive without touching a row of it. The family's stories stay
     * in Room; this only forgets which one was open. Deleting an archive is a separate,
     * deliberate act and does not belong behind a sign-out button.
     */
    fun clear() {
        familyId = null
        userId = null
        familyName = null
        role = null
        ancestorIds = emptySet()
        personIds = emptySet()
        prefs?.edit()
            ?.remove(KEY_FAMILY)
            ?.remove(KEY_USER)
            ?.remove(KEY_FAMILY_NAME)
            ?.remove(KEY_ROLE)
            ?.apply()
    }

    /**
     * Signs out of the account. Closes the archive too, because an open archive with no
     * account behind it is a state nothing else in the app knows how to reason about.
     * Still touches no rows: the family's stories stay in Room for whoever signs in next.
     */
    fun clearAuth() {
        clear()
        authUid = null
        authEmail = null
        prefs?.edit()?.remove(KEY_AUTH_UID)?.remove(KEY_AUTH_EMAIL)?.apply()
    }
}
