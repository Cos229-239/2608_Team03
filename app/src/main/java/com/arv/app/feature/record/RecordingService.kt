package com.arv.app.feature.record

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.arv.app.ArvApp
import com.arv.app.MainActivity
import com.arv.app.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

data class RecordingState(
    val isRecording: Boolean = false,
    val isPaused: Boolean = false,
    val elapsedMs: Long = 0L,
    /** Normalized 0..1, sampled ~20x/sec for the waveform. */
    val amplitudes: List<Float> = emptyList(),
    val outputPath: String? = null,
    val error: String? = null,
    /**
     * [elapsedMs] the last time the input hit the ceiling, or null if it never has.
     *
     * Clipping is destructive and it is destructive at capture: once a peak is flattened
     * the shape of that sound is gone, and no amount of care afterwards brings it back. It
     * is heard on playback as a crackle or a tear. The only moment anyone can do anything
     * about it is while the recording is still running, which is why this is surfaced live
     * instead of discovered later.
     */
    val lastClippedAtMs: Long? = null
) {
    /** True for a few seconds after the input last hit the ceiling. */
    val isClipping: Boolean
        get() = lastClippedAtMs != null && elapsedMs - lastClippedAtMs <= CLIP_WARNING_MS

    /** True if any part of this recording clipped, however long ago. */
    val hasClipped: Boolean get() = lastClippedAtMs != null
}

/** How long the "too loud" warning stays up after the last peak. */
private const val CLIP_WARNING_MS = 2_500L

/**
 * Process-wide recording state.
 *
 * A bound service would be the textbook answer, but recording has to survive the Activity
 * being destroyed and rebuilt (rotation, the launcher, a phone call), and the UI needs to
 * reattach to a recording it did not start. A single observable holder is simpler and has
 * fewer failure modes than rebinding.
 */
object RecordingBus {
    private val _state = MutableStateFlow(RecordingState())
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    internal fun update(block: (RecordingState) -> RecordingState) {
        _state.value = block(_state.value)
    }

    internal fun reset() {
        _state.value = RecordingState()
    }

    /**
     * Throw the finished recording away, bytes and all.
     *
     * Distinct from [reset], which only forgets the recording after it has been saved into
     * the archive. This is the discard path, and it has to actually erase the file: an
     * archive people trust with a grandmother's voice cannot quietly keep the takes someone
     * chose not to keep. Returns false if the file was there and could not be removed, so
     * the caller never tells someone it is gone when it is not.
     */
    fun discard(): Boolean {
        val path = _state.value.outputPath
        // The whole delete has to be inside runCatching. File(path) alone cannot throw,
        // and Result.map does not catch, so a SecurityException from delete() used to
        // propagate out of a function whose entire job is to report success honestly.
        val erased = path == null || runCatching {
            val f = File(path)
            !f.exists() || f.delete()
        }.getOrDefault(false)

        // State clears either way. Leaving a pointer to a file we failed to delete would
        // offer Save on a take the person just said they did not want kept.
        _state.value = RecordingState()
        return erased
    }
}

/**
 * CAP-2. Keeps a long interview alive with the screen off.
 *
 * Everything here is written for the 45-minute case, not the 30-second case: audio goes
 * straight to disk, the file is finalized on every stop path, and an interruption
 * (a phone call, the OS reclaiming the mic) stops cleanly with the partial file intact
 * rather than throwing away what was already said.
 */
class RecordingService : LifecycleService() {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startedAtElapsed: Long = 0L
    private var accumulatedMs: Long = 0L
    private var tickJob: Job? = null

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> start()
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            ACTION_STOP -> stop()
        }
        // If the system kills us, do not silently restart with no user present.
        return START_NOT_STICKY
    }

    private fun start() {
        if (recorder != null) return

        val dir = File(filesDir, "recordings").apply { mkdirs() }
        val file = File(dir, "${UUID.randomUUID()}.m4a")
        outputFile = file

        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        // Foreground FIRST, before anything that can throw.
        //
        // send() starts us with startForegroundService, which hands the process a short
        // deadline to call startForeground or be killed. prepare()/start() throw on the
        // ordinary case of the microphone being busy, which is exactly "someone rang while
        // she was about to start talking", and the old order took the catch branch to
        // stopSelf() having never gone foreground.
        startForegroundCompat()

        try {
            rec.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(48_000)
                setAudioEncodingBitRate(128_000)
                setAudioChannels(1)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
        } catch (e: Exception) {
            rec.runCatching { release() }
            // prepare() creates the file before it throws, so a failed start leaves a
            // zero byte .m4a behind. Nothing will ever reference it; take it with us.
            runCatching { if (file.length() == 0L) file.delete() }
            outputFile = null
            RecordingBus.update {
                RecordingState(error = e.message ?: "Could not start recording")
            }
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        recorder = rec
        accumulatedMs = 0L
        startedAtElapsed = SystemClock.elapsedRealtime()

        RecordingBus.update {
            RecordingState(isRecording = true, outputPath = file.absolutePath)
        }
        startTicking()
    }

    private fun pause() {
        val rec = recorder ?: return
        if (RecordingBus.state.value.isPaused) return
        runCatching { rec.pause() }
            .onFailure { RecordingBus.update { s -> s.copy(error = it.message) } }
            .onSuccess {
                accumulatedMs += SystemClock.elapsedRealtime() - startedAtElapsed
                tickJob?.cancel()
                RecordingBus.update { it.copy(isPaused = true, elapsedMs = accumulatedMs) }
            }
    }

    private fun resume() {
        val rec = recorder ?: return
        if (!RecordingBus.state.value.isPaused) return
        runCatching { rec.resume() }
            .onFailure { RecordingBus.update { s -> s.copy(error = it.message) } }
            .onSuccess {
                startedAtElapsed = SystemClock.elapsedRealtime()
                RecordingBus.update { it.copy(isPaused = false) }
                startTicking()
            }
    }

    private fun stop() {
        // Nothing to stop. Without this guard a second Stop tap adds another elapsed
        // delta on top of the first, so a 5 minute interview saves as 10; and an
        // ACTION_STOP delivered to a fresh service instance measures from
        // startedAtElapsed = 0, producing a duration of "milliseconds since the phone
        // booted" and overwriting outputPath with null, which strands the finished .m4a
        // on disk with no way back to it from the UI.
        val rec = recorder ?: return
        tickJob?.cancel()
        recorder = null

        // stop() throws if the recording was too short to produce a valid file. Release
        // either way so the mic is never left held, and keep whatever bytes landed.
        rec.runCatching { stop() }
        rec.runCatching { release() }

        if (!RecordingBus.state.value.isPaused) {
            accumulatedMs += SystemClock.elapsedRealtime() - startedAtElapsed
        }

        RecordingBus.update {
            it.copy(
                isRecording = false,
                isPaused = false,
                elapsedMs = accumulatedMs,
                outputPath = outputFile?.absolutePath
            )
        }

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Samples amplitude for the waveform and advances the clock the UI displays. */
    private fun startTicking() {
        tickJob?.cancel()
        tickJob = lifecycleScope.launch {
            while (isActive) {
                delay(TICK_MS)
                val rec = recorder ?: break
                val amplitude = runCatching { rec.maxAmplitude }.getOrDefault(0)
                val normalized = (amplitude / MAX_AMPLITUDE).coerceIn(0f, 1f)
                val elapsed = accumulatedMs + (SystemClock.elapsedRealtime() - startedAtElapsed)
                // maxAmplitude returns the loudest sample since the last read, so a peak
                // at or near full scale means the input has run out of headroom and is
                // being flattened. Nothing later can undo that, so it is reported now.
                val clipped = normalized >= CLIP_LEVEL
                RecordingBus.update { state ->
                    state.copy(
                        elapsedMs = elapsed,
                        amplitudes = (state.amplitudes + normalized).takeLast(WAVEFORM_WINDOW),
                        lastClippedAtMs = if (clipped) elapsed else state.lastClippedAtMs
                    )
                }
            }
        }
    }

    private fun startForegroundCompat() {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification =
            NotificationCompat.Builder(this, ArvApp.CHANNEL_RECORDING)
                .setContentTitle(getString(R.string.recording_notification_title))
                .setContentText(getString(R.string.recording_notification_text))
                .setSmallIcon(android.R.drawable.presence_audio_online)
                .setContentIntent(open)
                .setOngoing(true)
                .setSilent(true)
                .build()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    override fun onDestroy() {
        tickJob?.cancel()
        val rec = recorder
        recorder = null

        if (rec != null) {
            // We are being torn down mid-recording: the OS reclaimed us, or the mic was
            // taken. Finalize the file and tell the UI, because the alternative is a
            // screen that still says "Recording. The screen can turn off." over a frozen
            // clock, whose Stop button reaches a fresh instance and orphans the take.
            rec.runCatching { stop() }
            rec.runCatching { release() }

            if (!RecordingBus.state.value.isPaused) {
                accumulatedMs += SystemClock.elapsedRealtime() - startedAtElapsed
            }
            RecordingBus.update {
                it.copy(
                    isRecording = false,
                    isPaused = false,
                    elapsedMs = accumulatedMs,
                    outputPath = outputFile?.absolutePath,
                    error = "Recording stopped early. What was recorded up to that point is saved."
                )
            }
        }
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "arv.record.START"
        const val ACTION_PAUSE = "arv.record.PAUSE"
        const val ACTION_RESUME = "arv.record.RESUME"
        const val ACTION_STOP = "arv.record.STOP"

        private const val NOTIFICATION_ID = 1001
        private const val TICK_MS = 50L
        private const val WAVEFORM_WINDOW = 96
        private const val MAX_AMPLITUDE = 32_767f

        /**
         * Peak fraction at which the input is treated as out of headroom.
         *
         * Not 1.0. An ADC that is being driven too hard rounds its peaks down slightly, so
         * waiting for a literal full-scale sample misses most real clipping.
         */
        private const val CLIP_LEVEL = 0.98f

        fun send(context: Context, action: String) {
            val intent = Intent(context, RecordingService::class.java).setAction(action)
            if (action == ACTION_START) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
