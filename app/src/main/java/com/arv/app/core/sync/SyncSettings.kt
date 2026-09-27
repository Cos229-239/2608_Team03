package com.arv.app.core.sync

import android.content.Context
import android.content.SharedPreferences
import com.arv.app.core.session.ActiveSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether this phone shares an archive, and how the last attempt went.
 *
 * Per archive and off until somebody turns it on. Nothing about a family leaves a phone
 * because an update arrived; it leaves because a person on that phone chose it, for that
 * family. SharedPreferences rather than Room for the reason [ActiveSession] gives: the answer
 * is wanted synchronously, by a worker deciding whether to do anything at all.
 */
object SyncSettings {

    private const val PREFS = "arv.sync"

    private val _version = MutableStateFlow(0L)

    /** Moves whenever anything here changes, so a screen showing it can redraw. */
    val version: StateFlow<Long> = _version.asStateFlow()

    fun bump() {
        _version.value = _version.value + 1
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isOn(context: Context, familyId: String): Boolean =
        prefs(context).getBoolean("on.$familyId", false)

    fun setOn(context: Context, familyId: String, on: Boolean) {
        prefs(context).edit().putBoolean("on.$familyId", on).apply()
        bump()
    }

    /**
     * The archive sync may act on right now, or null when there is none.
     *
     * The server knows people by the account Firebase signed in. An archive opened without
     * that account behind it, like the sample family or one made before accounts existed,
     * has no standing there, and nothing in it is sent anywhere.
     */
    fun eligibleFamily(): String? {
        val familyId = ActiveSession.familyId ?: return null
        val uid = ActiveSession.authUid ?: return null
        return familyId.takeIf { ActiveSession.userId == uid }
    }

    /** The family record exists on the server, so this phone need not check again. */
    fun isRegistered(context: Context, familyId: String): Boolean =
        prefs(context).getBoolean("registered.$familyId", false)

    fun markRegistered(context: Context, familyId: String) {
        prefs(context).edit().putBoolean("registered.$familyId", true).apply()
    }

    enum class Outcome {
        UP_TO_DATE,
        /** Some of it went; the rest waits for a connection. */
        OFFLINE,
        /** The server does not count this account as in the family. */
        NOT_A_MEMBER,
        /** Firebase has nobody signed in, or somebody other than this archive's account. */
        SIGNED_OUT,
        /** Sent what it had, but part of the pull was refused, so nothing came down. */
        PULL_REFUSED
    }

    data class Last(val at: Long, val outcome: Outcome, val refused: Int)

    fun last(context: Context, familyId: String): Last? {
        val p = prefs(context)
        val outcome = p.getString("outcome.$familyId", null)
            ?.let { name -> Outcome.entries.firstOrNull { it.name == name } } ?: return null
        return Last(p.getLong("at.$familyId", 0L), outcome, p.getInt("refused.$familyId", 0))
    }

    fun record(context: Context, familyId: String, outcome: Outcome, refused: Int, at: Long) {
        prefs(context).edit()
            .putString("outcome.$familyId", outcome.name)
            .putLong("at.$familyId", at)
            .putInt("refused.$familyId", refused)
            .apply()
        bump()
    }
}
