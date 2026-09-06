package com.arv.app.feature.record

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arv.app.core.di.ServiceLocator
import com.arv.app.ui.components.formatElapsed

/** Identity for an unsaved recording inside the playback controller. */
internal const val DRAFT_PLAYBACK_KEY = "review-draft"

/**
 * Hearing a recording back before it is committed to anything.
 *
 * Shared by the two screens that stand between a recording and the archive: reviewing a
 * new story, and adding a take to a story that already exists. It lives here rather than
 * inside either screen so the two cannot drift into behaving differently, which matters
 * because the subtleties below were each fixed once and should not have to be fixed twice.
 *
 * @param label what to call this take, since "recorded just now" is only true sometimes.
 */
@Composable
fun DraftPlayer(
    localAudioPath: String,
    durationMs: Long,
    modifier: Modifier = Modifier,
    label: String = "Recorded just now"
) {
    val playback by ServiceLocator.playback.state.collectAsStateWithLifecycle()
    val isThisDraft = playback.storyId == DRAFT_PLAYBACK_KEY
    val playable = ServiceLocator.playback.canPlay(localAudioPath)
    val positionMs = if (isThisDraft) playback.positionMs else 0L
    val totalMs = if (isThisDraft && playback.durationMs > 0L) playback.durationMs else durationMs

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                Text(formatElapsed(totalMs), style = MaterialTheme.typography.bodyMedium)
            }

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = {
                        ServiceLocator.playback.toggle(DRAFT_PLAYBACK_KEY, localAudioPath)
                    },
                    enabled = playable,
                    modifier = Modifier.size(48.dp)
                ) {
                    val playing = isThisDraft && playback.isPlaying
                    Icon(
                        if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription =
                            if (playing) "Pause" else "Play what you just recorded"
                    )
                }

                // Seeking happens on release, not on every touch sample. Seeking while
                // dragging starts audible playback the moment you touch the bar to check
                // the length, and each seek on an inactive draft builds a MediaPlayer and
                // calls prepare() on the main thread, so the thumb also fights the
                // position ticker.
                var scrub by remember { mutableStateOf<Float?>(null) }
                Slider(
                    value = scrub ?: if (totalMs > 0L) positionMs.toFloat() / totalMs else 0f,
                    onValueChange = { scrub = it },
                    onValueChangeFinished = {
                        scrub?.let { fraction ->
                            ServiceLocator.playback.seekTo(
                                DRAFT_PLAYBACK_KEY,
                                localAudioPath,
                                (fraction * totalMs).toLong()
                            )
                        }
                        scrub = null
                    },
                    enabled = playable,
                    modifier = Modifier.weight(1f)
                )

                Text(formatElapsed(positionMs), style = MaterialTheme.typography.bodySmall)
            }

            if (!playable) {
                Text(
                    "The audio file is missing, so there is nothing to play back.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
