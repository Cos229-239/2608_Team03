package com.arv.app.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * What is currently playing, if anything. One story at a time, app-wide: a family
 * archive is a room where one voice speaks and everyone listens, not a mixer.
 */
data class PlaybackState(
    val storyId: String? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L
) {
    val isActive: Boolean get() = storyId != null
}

/**
 * The one place recorded voices come out of. MediaPlayer behind a StateFlow, so any
 * screen can show what is playing and every play button in the app behaves the same.
 *
 * Seed data ships with "seed://" paths and no real audio; [canPlay] is how the UI finds
 * out honestly instead of throwing. A play button that cannot play should say why, not
 * pretend.
 */
private const val TAG = "ArvPlayback"

class PlaybackController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var player: MediaPlayer? = null
    private var ticker: Job? = null

    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null

    /**
     * Speech, not music. The distinction is not cosmetic: it tells the system to route
     * this to the media output rather than a notification stream, and it is what lets a
     * car stereo or a hearing aid treat a grandmother's voice as something to be
     * understood rather than something playing in the background.
     */
    private val attributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /** Called once from the Application so playback can take audio focus. */
    fun attach(context: Context) {
        audioManager = context.applicationContext
            .getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }

    /**
     * Take the floor before a voice plays, and give it back after. Without this, music
     * from another app keeps playing over the top of the recording, which is the exact
     * situation this app exists to prevent.
     */
    private fun requestFocus(): Boolean {
        val am = audioManager ?: return true
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener { change ->
                    when (change) {
                        AudioManager.AUDIOFOCUS_LOSS -> stop()
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pause()
                    }
                }
                .build()
            focusRequest = req
            am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            true
        }
    }

    private fun abandonFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { am.abandonAudioFocusRequest(it) }
            focusRequest = null
        }
    }

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    fun canPlay(localPath: String?): Boolean =
        localPath != null && !localPath.startsWith("seed://") && File(localPath).exists()

    /**
     * Play a story's audio from the start, or toggle pause if it is already the active
     * story. Starting a different story stops the current one first.
     */
    fun toggle(storyId: String, localPath: String) {
        val current = _state.value
        if (current.storyId == storyId && player != null) {
            if (current.isPlaying) pause() else resume()
            return
        }
        start(storyId, localPath, startAtMs = 0L)
    }

    /** Jump to a moment in a story, starting playback there. The transcript's timestamps
     *  are the map; this is what makes them doors instead of decoration. */
    fun seekTo(storyId: String, localPath: String, positionMs: Long) {
        val current = _state.value
        if (current.storyId == storyId && player != null) {
            player?.seekTo(positionMs.toInt())
            _state.value = current.copy(positionMs = positionMs)
            if (!current.isPlaying) resume()
            return
        }
        start(storyId, localPath, startAtMs = positionMs)
    }

    fun pause() {
        val mp = player
        ticker?.cancel()
        if (mp == null) {
            // No player behind the state. Say so rather than sit on a paused-looking
            // control that will never resume.
            _state.value = PlaybackState()
            return
        }
        if (mp.isPlaying) mp.pause()
        _state.value = _state.value.copy(isPlaying = false)
    }

    fun stop() {
        ticker?.cancel()
        player?.release()
        player = null
        abandonFocus()
        _state.value = PlaybackState()
    }

    private fun resume() {
        val mp = player
        if (mp == null) {
            // This is the bug that makes a play button lie: without the null check the
            // state flips to isPlaying with nothing behind it, and the UI shows Pause
            // over silence.
            _state.value = PlaybackState()
            return
        }
        mp.start()
        _state.value = _state.value.copy(isPlaying = true)
        startTicker()
    }

    private fun start(storyId: String, localPath: String, startAtMs: Long) {
        if (!canPlay(localPath)) return
        stop()
        val mp = MediaPlayer()
        player = mp
        runCatching {
            // Attributes must be set before prepare(), or the player keeps whatever
            // default routing it was constructed with. setVolume, by contrast, is only
            // legal once the player is prepared; calling it in Idle throws.
            mp.setAudioAttributes(attributes)
            mp.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "MediaPlayer error what=$what extra=$extra path=$localPath")
                false
            }
            requestFocus()
            mp.setDataSource(localPath)
            mp.prepare()
            mp.setVolume(1f, 1f)
            if (startAtMs > 0) mp.seekTo(startAtMs.toInt())
            mp.setOnCompletionListener {
                ticker?.cancel()
                _state.value = _state.value.copy(
                    isPlaying = false,
                    positionMs = _state.value.durationMs
                )
            }
            mp.start()
            _state.value = PlaybackState(
                storyId = storyId,
                isPlaying = true,
                positionMs = startAtMs,
                durationMs = mp.duration.toLong()
            )
            startTicker()
        }.onFailure { t ->
            // A broken file must not take the screen down with it, but swallowing the
            // reason silently is how a play button ends up looking fine and playing
            // nothing. Say what happened.
            Log.e(TAG, "playback failed for $localPath", t)
            stop()
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (true) {
                delay(250)
                val mp = player ?: break
                runCatching {
                    _state.value = _state.value.copy(positionMs = mp.currentPosition.toLong())
                }
            }
        }
    }
}
