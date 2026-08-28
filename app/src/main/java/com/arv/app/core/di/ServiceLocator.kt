package com.arv.app.core.di

import android.content.Context
import com.arv.app.core.audio.PlaybackController
import com.arv.app.core.ai.ClinicalClaimGuard
import com.arv.app.core.ai.FakeTranscriptionService
import com.arv.app.core.ai.GroundingEnforcer
import com.arv.app.core.ai.LibrarianHive
import com.arv.app.core.ai.LibrarianService
import com.arv.app.core.ai.TranscriptionService
import com.arv.app.core.ai.VoskModelStore
import com.arv.app.core.ai.VoskTranscriptionService
import com.arv.app.core.ai.Viewer
import com.arv.app.core.data.StoryRepository
import com.arv.app.core.data.local.ArvDatabase
import com.arv.app.core.model.MemberRole
import com.arv.app.core.session.ActiveSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Deliberately not Hilt.
 *
 * Four people are learning Android on a four-week clock; a DI framework would cost more in
 * build errors and annotation-processor confusion than it saves at this size. If the graph
 * outgrows this file in PnP3, that is the moment to introduce Hilt.
 */
object ServiceLocator {

    @Volatile private var repository: StoryRepository? = null
    @Volatile private var librarian: LibrarianService? = null

    /**
     * The sample family. Kept deliberately: it is what the class demo and the team video
     * run on, and it is the one archive that can be shown to strangers. Real families get
     * their own id from [com.arv.app.core.session.ActiveSession] and never mix with it.
     */
    const val DEMO_FAMILY_ID = "demo-family"
    const val DEMO_USER_ID = "u_dana"

    /**
     * The family every screen reads. One property, ten call sites, so switching archives
     * is a session change rather than a refactor.
     *
     * Falls back to the sample family only when nothing is signed in, which onboarding
     * makes impossible in practice. The fallback exists so a Compose preview or a test
     * that skips onboarding still renders something instead of crashing.
     */
    val familyId: String get() = ActiveSession.familyId ?: DEMO_FAMILY_ID

    val userId: String get() = ActiveSession.userId ?: DEMO_USER_ID

    /**
     * Who is asking, for the permission filter. Defined once on purpose: a screen that
     * builds its own [Viewer] is a screen that can quietly disagree with the others about
     * what someone is allowed to read, and this app's whole claim is that they never do.
     *
     * TODO(DAT-1): role and branch root belong to the family membership record. Until that
     * exists this is OWNER, which is true of the only member a device currently has.
     */
    val viewer: Viewer
        get() = Viewer(
            userId = userId,
            familyId = familyId,
            role = MemberRole.OWNER,
            ancestorIds = ActiveSession.ancestorIds,
            personIds = ActiveSession.personIds
        )

    /**
     * Work that has to outlive the screen that started it.
     *
     * Transcription is the case this exists for. Review saves a story and navigates away
     * in the same breath, which pops the back stack entry and cancels its ViewModel scope,
     * so anything launched there dies within milliseconds of starting. A recording is the
     * one thing in this app that cannot be asked to happen again, and the work that turns
     * it into searchable text should not be tied to whether someone stayed on a screen.
     *
     * TODO(DAT-2): a WorkManager job survives process death as well; this survives only
     * navigation. WorkManager is already a declared dependency for that step.
     */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun storyRepository(context: Context): StoryRepository =
        repository ?: synchronized(this) {
            repository ?: StoryRepository(ArvDatabase.get(context)).also { repository = it }
        }

    /**
     * The librarian is always wrapped in [GroundingEnforcer]. Constructing a bare
     * LibrarianService anywhere else in the app is a bug: the sources-required guarantee
     * from docs/PITCH.md slide 6 lives in that wrapper, not in a prompt.
     */
    fun librarianService(context: Context): LibrarianService =
        librarian ?: synchronized(this) {
            librarian ?: run {
                val repo = storyRepository(context)
                // Order matters. Grounding runs innermost so an ungrounded answer is gone
                // before the clinical guard ever inspects it. The hive replaced the flat
                // pipeline as the wired retrieval; the guards wrap it unchanged, which is
                // the whole point of keeping them as decorators.
                ClinicalClaimGuard(
                    GroundingEnforcer(
                        LibrarianHive(
                            storiesProvider = { familyId -> repo.allForLibrarian(familyId) },
                            peopleProvider = { familyId -> repo.peopleFor(familyId) },
                            segmentsForStory = { storyId -> repo.transcriptForStory(storyId) }
                        )
                    )
                ).also { librarian = it }
            }
        }

    @Volatile private var models: VoskModelStore? = null

    /** The on-device speech model, and the one-time setup that puts it there. */
    fun voskModelStore(context: Context): VoskModelStore =
        models ?: synchronized(this) {
            models ?: VoskModelStore(context.applicationContext).also { models = it }
        }

    /**
     * AI-2. Real recognition when the model is on the phone, an honest placeholder before.
     *
     * The fallback is deliberate rather than a stub left behind. Until someone has run the
     * one-time setup there is nothing that can read the audio, and the placeholder says
     * exactly that. What it must never do is invent plausible sentences and file them next
     * to a real recording, which is what the old fake did.
     */
    fun transcriptionService(context: Context): TranscriptionService {
        val store = voskModelStore(context)
        return if (store.isReady) VoskTranscriptionService(store) else FakeTranscriptionService()
    }

    /** One voice at a time, app-wide. Every play button goes through here. */
    val playback: PlaybackController by lazy { PlaybackController() }
}
