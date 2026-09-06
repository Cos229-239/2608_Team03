package com.arv.app.core.di

import android.content.Context
import com.arv.app.core.audio.PlaybackController
import com.arv.app.core.ai.ClinicalClaimGuard
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
     * Falls back to the sample family only when nobody is signed in at all: a Compose
     * preview, a test that skips onboarding. An account with no archive open has no honest
     * answer here, and handing it the sample family would let a real person's first story
     * land in the demo. Routing keeps that state on the onboarding screen, which never
     * asks; if something else asks, failing loudly beats writing into the wrong family.
     */
    val familyId: String
        get() = ActiveSession.familyId ?: run {
            check(!ActiveSession.isAuthenticated) { "No archive is open for this account." }
            DEMO_FAMILY_ID
        }

    val userId: String get() = ActiveSession.userId ?: ActiveSession.authUid ?: DEMO_USER_ID

    /**
     * Who is asking, for the permission filter. Defined once on purpose: a screen that
     * builds its own [Viewer] is a screen that can quietly disagree with the others about
     * what someone is allowed to read, and this app's whole claim is that they never do.
     *
     * The role is this account's member row, cached on the session. Inside a real archive
     * an unproven role is VIEWER, the one role that can only read what the family already
     * shows everyone. With no archive open at all the viewer is the sample family's owner,
     * the same fallback [familyId] and [userId] make, so a preview renders as the demo.
     */
    val viewer: Viewer
        get() = Viewer(
            userId = userId,
            familyId = familyId,
            role = ActiveSession.role
                ?: if (ActiveSession.isSignedIn) MemberRole.VIEWER else MemberRole.OWNER,
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
    /**
     * Null until the speech model is installed.
     *
     * The placeholder service this used to fall back to reported success, so its stand-in
     * sentence was written to the transcript table and the story marked READY: permanent,
     * searchable, and never redone once the real model arrived. No model means no
     * transcription happened, and the only honest status for that is the one the story
     * already has, PENDING.
     */
    fun transcriptionService(context: Context): TranscriptionService? {
        val store = voskModelStore(context)
        return if (store.isReady) VoskTranscriptionService(store) else null
    }

    /** One voice at a time, app-wide. Every play button goes through here. */
    val playback: PlaybackController by lazy { PlaybackController() }
}
