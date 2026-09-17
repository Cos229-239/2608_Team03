package com.arv.app.core.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.arv.app.core.data.local.ArvDatabase
import com.arv.app.core.di.ServiceLocator
import com.arv.app.core.model.MemberRole
import com.arv.app.core.remote.RemoteWrite
import com.arv.app.core.session.ActiveSession
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * One sync of the open archive, run by WorkManager so it survives the screen that asked for it,
 * waits for a connection, and retries on its own when there was none.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val familyId = SyncSettings.eligibleFamily() ?: return Result.success()
        val userId = ActiveSession.userId ?: return Result.success()
        if (!SyncSettings.isOn(context, familyId)) return Result.success()

        val engine = ServiceLocator.syncEngine(context)
        if (!engine.available) return Result.success()
        val now = System.currentTimeMillis()

        // The rules read the account Firebase has signed in, not the one this phone remembers.
        // If they differ, every write would be refused and recorded as refused, which is a
        // lie about the family's rules. Stop and say what is actually wrong.
        val signedIn = runCatching { FirebaseAuth.getInstance().currentUser?.uid }.getOrNull()
        if (signedIn != userId) {
            SyncSettings.record(context, familyId, SyncSettings.Outcome.SIGNED_OUT, 0, now)
            return Result.success()
        }

        val repo = ServiceLocator.storyRepository(context)

        // A family whose owner never handed out a code has never been on the server, and the
        // rules refuse every write into a family they have not heard of. Its owner puts it
        // there first. Anyone else got in through the server, so for them it already is.
        if (!SyncSettings.isRegistered(context, familyId)) {
            val row = repo.memberRowFor(familyId, userId)
            if (row?.role == MemberRole.OWNER) {
                when (ServiceLocator.inviteRemote().registerFamily(familyId, ActiveSession.familyName ?: "", row)) {
                    RemoteWrite.Done -> SyncSettings.markRegistered(context, familyId)
                    RemoteWrite.Failed -> {
                        SyncSettings.record(context, familyId, SyncSettings.Outcome.OFFLINE, 0, now)
                        return Result.retry()
                    }
                    RemoteWrite.Skipped -> return Result.success()
                }
            } else {
                SyncSettings.markRegistered(context, familyId)
            }
        }

        val pull = inputData.getBoolean(KEY_PULL, false)
        val result = engine.run(familyId, userId, pull)
        val at = System.currentTimeMillis()
        return when (result) {
            is SyncEngine.Result.Done -> {
                SyncSettings.record(
                    context, familyId,
                    if (result.pullRefused) SyncSettings.Outcome.PULL_REFUSED else SyncSettings.Outcome.UP_TO_DATE,
                    result.refused, at
                )
                // Somebody's role, or the tree their branch is read from, may have just changed.
                if (result.merged?.changesAnything == true) {
                    runCatching { repo.refreshLineage(familyId, userId) }
                }
                // An edit made while the pull was out is still waiting, and the request it made
                // found this run already going. A send picks it up. Only after a pull: a pull
                // settles anything the server was ahead on, so what is left really is new, and
                // a send never asks for another send, so this cannot go round in a loop.
                if (pull && SyncScheduler.observeWaiting(context, familyId).first() > 0) {
                    SyncScheduler.sendSoon(context)
                }
                Result.success()
            }
            SyncEngine.Result.Offline -> {
                SyncSettings.record(context, familyId, SyncSettings.Outcome.OFFLINE, 0, at)
                Result.retry()
            }
            SyncEngine.Result.NotAMember -> {
                SyncSettings.record(context, familyId, SyncSettings.Outcome.NOT_A_MEMBER, 0, at)
                Result.success()
            }
            SyncEngine.Result.NoServer -> Result.success()
        }
    }

    companion object {
        const val KEY_PULL = "pull"
    }
}

/** Asks for sync at the moments it is worth having. */
object SyncScheduler {

    /** Sends only. Asked for a moment after edits on this phone stop. */
    const val SEND = "arv.sync.send"

    /** Sends, then brings down what the family added. */
    const val FULL = "arv.sync.full"

    /** Long enough that typing a title is one send, not one per letter. */
    private const val QUIET_MS = 3_000L

    /** Coming back to the app pulls at most this often. */
    private const val VISIBLE_GAP_MS = 60_000L

    @Volatile private var lastVisiblePull = 0L

    fun sendSoon(context: Context) = enqueue(context, SEND, pull = false)

    fun syncNow(context: Context) = enqueue(context, FULL, pull = true)

    /** The app came to the front: whatever the family added since, bring it down. */
    fun onAppVisible(context: Context, now: Long = System.currentTimeMillis()) {
        // The archive may have changed while the app was away. Anything watching re-reads.
        SyncSettings.bump()
        if (now - lastVisiblePull < VISIBLE_GAP_MS) return
        lastVisiblePull = now
        syncNow(context)
    }

    fun stop(context: Context) {
        val work = WorkManager.getInstance(context.applicationContext)
        work.cancelUniqueWork(SEND)
        work.cancelUniqueWork(FULL)
    }

    /**
     * Gives everything the server turned down one more try. For when something may have
     * changed that the phone cannot see, like the owner making this account a contributor.
     */
    suspend fun forgetRefusals(context: Context, familyId: String) {
        val db = ArvDatabase.get(context.applicationContext)
        db.storyDao().clearRefusals(familyId)
        db.personDao().clearRefusals(familyId)
        db.relationshipDao().clearRefusals(familyId)
    }

    private fun enqueue(context: Context, name: String, pull: Boolean) {
        val app = context.applicationContext
        val familyId = SyncSettings.eligibleFamily() ?: return
        if (!SyncSettings.isOn(app, familyId)) return
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(workDataOf(SyncWorker.KEY_PULL to pull))
            .build()
        // KEEP: a sync already waiting will pick this edit up too, because it reads what is
        // unsent when it starts rather than when it was asked for. One already running may
        // have read past it; the worker checks for that when it finishes.
        WorkManager.getInstance(app).enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, request)
    }

    /** True while either kind of sync is running. */
    fun observeRunning(context: Context): Flow<Boolean> {
        val work = WorkManager.getInstance(context.applicationContext)
        return combine(
            work.getWorkInfosForUniqueWorkFlow(SEND),
            work.getWorkInfosForUniqueWorkFlow(FULL)
        ) { send, full -> (send + full).any { it.state == WorkInfo.State.RUNNING } }
    }

    /**
     * How many changes on this phone the family's server does not have yet, counted with the
     * same rules sync sends by, so a private story is never counted as waiting to go.
     */
    fun observeWaiting(context: Context, familyId: String): Flow<Int> =
        observeWaitingVersions(context, familyId).map { it.size }

    /**
     * Every waiting change as its row and version. A second edit to a row that was already
     * waiting leaves the count alone but changes this, which is how [watch] notices it.
     */
    private fun observeWaitingVersions(context: Context, familyId: String): Flow<Set<String>> {
        val db = ArvDatabase.get(context.applicationContext)
        return combine(
            db.storyDao().observeUnsynced(familyId),
            db.personDao().observeUnsynced(familyId),
            db.relationshipDao().observeUnsynced(familyId),
            db.outboxDao().observePendingDeletes(SyncPaths.relationships(familyId))
        ) { stories, people, edges, removals ->
            stories.filter { SyncPolicy.decide(it) != SyncPolicy.StoryAction.Nothing }
                .map { "s:${it.storyId}:${it.updatedAt}" }.toSet() +
                people.filter { SyncPolicy.hasNews(it.updatedAt, it.syncedAt, it.refusedAt) }
                    .map { "p:${it.personId}:${it.updatedAt}" } +
                edges.filter { SyncPolicy.hasNews(it.updatedAt, it.syncedAt, it.refusedAt) }
                    .map { "r:${SyncDocs.edgeId(it)}:${it.updatedAt}" } +
                removals.map { "x:${it.id}" }
        }
    }

    /**
     * Watches for edits to the open archive and asks for a send once they go quiet. Started
     * once, by the application. Follows the archive and the switch as they change.
     */
    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    fun watch(context: Context, scope: CoroutineScope): Job {
        val app = context.applicationContext
        return scope.launch {
            SyncSettings.version
                .map { SyncSettings.eligibleFamily()?.takeIf { SyncSettings.isOn(app, it) } }
                .distinctUntilChanged()
                .flatMapLatest { familyId ->
                    if (familyId == null) flowOf(emptySet()) else observeWaitingVersions(app, familyId)
                }
                .distinctUntilChanged()
                .debounce(QUIET_MS)
                .collect { waiting -> if (waiting.isNotEmpty()) sendSoon(app) }
        }
    }
}
