package com.arv.app.core.di

import android.content.Context
import com.arv.app.core.audio.PlaybackController
import com.arv.app.core.ai.ClinicalClaimGuard
import com.arv.app.core.ai.FakeTranscriptionService
import com.arv.app.core.ai.GroundingEnforcer
import com.arv.app.core.ai.LibrarianHive
import com.arv.app.core.ai.LibrarianService
import com.arv.app.core.ai.TranscriptionService
import com.arv.app.core.data.StoryRepository
import com.arv.app.core.data.local.ArvDatabase
import com.arv.app.core.session.ActiveSession

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

    /**
     * AI-1 ships the fake so the other three roles are never blocked on a provider account.
     * AI-2 swaps in the real implementation here and nowhere else.
     */
    var transcriptionService: TranscriptionService = FakeTranscriptionService()
        internal set

    /** One voice at a time, app-wide. Every play button goes through here. */
    val playback: PlaybackController by lazy { PlaybackController() }
}
