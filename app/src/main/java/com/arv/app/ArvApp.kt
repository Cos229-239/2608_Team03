package com.arv.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.core.content.getSystemService

class ArvApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createRecordingChannel()
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
