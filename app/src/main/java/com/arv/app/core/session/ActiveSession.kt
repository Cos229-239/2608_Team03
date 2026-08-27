package com.arv.app.core.session

import android.content.Context
import android.content.SharedPreferences

/**
 * Who is using the app, and whose archive they are standing in.
 *
 * Read synchronously and stored in SharedPreferences rather than Room on purpose: the
 * answer is needed before the first frame, to decide whether onboarding runs at all.
 * A suspending read would mean rendering the family feed before knowing which family.
 *
 * DAT-1 replaces the two ids with Firebase Auth's uid and the family that user belongs
 * to. Every screen already reads the family from [com.arv.app.core.di.ServiceLocator],
 * so that swap lands in [set] and [restore] and touches no UI.
 */
object ActiveSession {

    private const val PREFS = "arv.session"
    private const val KEY_FAMILY = "familyId"
    private const val KEY_USER = "userId"
    private const val KEY_FAMILY_NAME = "familyName"

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

    /** False on a fresh install, which is the only trigger for onboarding. */
    val isSignedIn: Boolean get() = familyId != null

    fun restore(context: Context) {
        val p = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        familyId = p.getString(KEY_FAMILY, null)
        userId = p.getString(KEY_USER, null)
        familyName = p.getString(KEY_FAMILY_NAME, null)
    }

    fun set(familyId: String, userId: String, familyName: String) {
        this.familyId = familyId
        this.userId = userId
        this.familyName = familyName
        prefs?.edit()
            ?.putString(KEY_FAMILY, familyId)
            ?.putString(KEY_USER, userId)
            ?.putString(KEY_FAMILY_NAME, familyName)
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
        prefs?.edit()?.clear()?.apply()
    }
}
