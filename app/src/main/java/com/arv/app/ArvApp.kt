package com.arv.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.core.content.getSystemService
import com.arv.app.core.di.ServiceLocator
import com.arv.app.core.session.ActiveSession
import kotlinx.coroutines.launch

class ArvApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Before the first frame. Which family is open decides whether onboarding runs,
        // and that question cannot be answered asynchronously without flashing the wrong
        // screen at someone.
        ActiveSession.restore(this)
        com.arv.app.ui.theme.ThemeController.restore(this)
        ServiceLocator.playback.attach(this)
        createRecordingChannel()

        // Work out who this person is in the family and who they descend from. BRANCH
        // visibility reads the result, and it starts empty, so until this finishes
        // branch-scoped material is hidden rather than shown. That is the right way round:
        // a moment of showing too little is a delay, a moment of showing too much is a
        // family reading something that was never meant for them.
        val familyId = ActiveSession.familyId
        val userId = ActiveSession.userId
        if (familyId != null && userId != null) {
            ServiceLocator.appScope.launch {
                runCatching { ServiceLocator.storyRepository(this@ArvApp).refreshLineage(familyId, userId) }
                // Whatever transcription the last run left behind, picked back up. A
                // crash must cost a retry, never a permanently stuck "Transcribing".
                runCatching {
                    ServiceLocator.storyRepository(this@ArvApp).recoverTranscriptions(
                        familyId, ServiceLocator.transcriptionService(this@ArvApp)
                    )
                }
            }
        }
    }

    /**
     * The recording notification is not marketing. It is the thing that keeps a
     * 45-minute interview alive when the screen locks, so it is IMPORTANCE_LOW
     * (visible, silent) rather than MIN. The user must be able to see it.
     */
    private fun createRecordingChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_RECORDING,
            getString(R.string.recording_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.recording_channel_description)
            setShowBadge(false)
        }
        getSystemService<NotificationManager>()?.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_RECORDING = "arv.recording"
    }
}
