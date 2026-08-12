package com.arv.app.core.di

import android.content.Context
import com.arv.app.core.ai.ClinicalClaimGuard
import com.arv.app.core.ai.FakeLibrarianService
import com.arv.app.core.ai.FakeTranscriptionService
import com.arv.app.core.ai.GroundingEnforcer
import com.arv.app.core.ai.LibrarianService
import com.arv.app.core.ai.TranscriptionService
import com.arv.app.core.data.StoryRepository
import com.arv.app.core.data.local.ArvDatabase

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

    /** TODO(DAT-1): replace with the signed-in user's active family. */
    const val DEMO_FAMILY_ID = "demo-family"

    /** TODO(DAT-1): replace with the signed-in user id. */
    const val DEMO_USER_ID = "u_dana"

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
                // before the clinical guard ever inspects it.
                ClinicalClaimGuard(
                    GroundingEnforcer(
                        FakeLibrarianService(
                            storiesProvider = { familyId -> repo.allForLibrarian(familyId) }
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
}
