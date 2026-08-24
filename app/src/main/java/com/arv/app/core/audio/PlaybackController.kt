package com.arv.app.core.audio

import android.media.MediaPlayer
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
class PlaybackController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var player: MediaPlayer? = null
    private var ticker: Job? = null

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
        player?.takeIf { it.isPlaying }?.pause()
        ticker?.cancel()
        _state.value = _state.value.copy(isPlaying = false)
    }

    fun stop() {
        ticker?.cancel()
        player?.release()
        player = null
        _state.value = PlaybackState()
    }

    private fun resume() {
        player?.start()
        _state.value = _state.value.copy(isPlaying = true)
        startTicker()
    }

    private fun start(storyId: String, localPath: String, startAtMs: Long) {
        if (!canPlay(localPath)) return
        stop()
        val mp = MediaPlayer()
        player = mp
        runCatching {
            mp.setDataSource(localPath)
            mp.prepare()
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
        }.onFailure {
            // A broken file must not take the screen down with it.
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
