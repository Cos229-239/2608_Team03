package com.arv.app.feature.record

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.Slider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arv.app.R
import com.arv.app.ui.components.Waveform
import com.arv.app.core.di.ServiceLocator
import com.arv.app.ui.components.formatElapsed

/**
 * Screen 04. Built for a real interview: the prompt stays visible for whoever is asking,
 * the clock is large enough to read across a kitchen table, and stopping is deliberate.
 */
@Composable
fun RecordScreen(
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val state by RecordingBus.state.collectAsStateWithLifecycle()

    // Erasing someone's voice is not a mistap away. Confirm first.
    var confirmDiscard by remember { mutableStateOf(false) }
    var discardFailed by remember { mutableStateOf(false) }

    // Playback started here has to end here. The draft player uses DRAFT_PLAYBACK_KEY,
    // which no play button elsewhere in the app matches, so audio that escapes this
    // screen has no stop control anywhere and just keeps talking over the next one.
    DisposableEffect(Unit) {
        onDispose { ServiceLocator.playback.stop() }
    }

    if (discardFailed) {
        AlertDialog(
            onDismissRequest = { discardFailed = false },
            title = { Text("Could not delete it") },
            text = {
                Text(
                    "The recording is still on this phone. Try again, and if it keeps " +
                        "failing the file can be removed from Settings."
                )
            },
            confirmButton = {
                TextButton(onClick = { discardFailed = false }) { Text("OK") }
            }
        )
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Delete this recording?") },
            text = {
                Text(
                    "The audio is erased from this phone and cannot be brought back. " +
                        "Nothing is saved to the archive."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        ServiceLocator.playback.stop()
                        // discard() returns false when the file is still on disk. Saying
                        // "deleted" over a file that survived is the one thing this
                        // button must never do.
                        if (!RecordingBus.discard()) {
                            discardFailed = true
                        }
                        confirmDiscard = false
                    }
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text("Keep it") }
            }
        )
    }

    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasMicPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasMicPermission) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // TODO(AI-8): replace with the generated prompt for the selected person.
        Card(colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Ask them",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    "\"What did your mother's kitchen smell like on a Sunday?\"",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        Spacer(Modifier.weight(1f))

        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                formatElapsed(state.elapsedMs),
                style = MaterialTheme.typography.displaySmall
            )
            Text(
                when {
                    state.error != null -> state.error!!
                    // Ranked above "Recording" on purpose. This is the only moment the
                    // damage can still be prevented, so it takes the line.
                    state.isClipping -> "Too loud. Move the phone back a little."
                    state.isPaused -> "Paused"
                    state.isRecording -> "Recording. The screen can turn off."
                    state.hasClipped -> "Some of that was too loud to record cleanly."
                    else -> "Ready"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.error != null || state.isClipping || state.hasClipped) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                textAlign = TextAlign.Center
            )
        }

        Waveform(amplitudes = state.amplitudes)

        Spacer(Modifier.weight(1f))

        if (!hasMicPermission) {
            Text(
                stringResource(R.string.permission_mic_rationale),
                style = MaterialTheme.typography.bodyMedium
            )
            Button(
                onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Allow microphone") }
        } else {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (state.isRecording) {
                    FilledIconButton(
                        onClick = {
                            RecordingService.send(
                                context,
                                if (state.isPaused) {
                                    RecordingService.ACTION_RESUME
                                } else {
                                    RecordingService.ACTION_PAUSE
                                }
                            )
                        },
                        modifier = Modifier.size(64.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Icon(
                            if (state.isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                            contentDescription = stringResource(
                                if (state.isPaused) R.string.record_resume else R.string.record_pause
                            )
                        )
                    }

                    FilledIconButton(
                        onClick = {
                            RecordingService.send(context, RecordingService.ACTION_STOP)
                        },
                        modifier = Modifier.size(88.dp)
                    ) {
                        Icon(
                            Icons.Filled.Stop,
                            contentDescription = stringResource(R.string.record_stop)
                        )
                    }
                } else {
                    Button(
                        onClick = {
                            ServiceLocator.playback.stop()
                            RecordingService.send(context, RecordingService.ACTION_START)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(64.dp)
                    ) { Text(stringResource(R.string.record_start)) }
                }
            }

            val finishedPath = state.outputPath
            if (!state.isRecording && finishedPath != null) {
                // Saving is a commitment, and nobody makes it on a recording of their
                // grandmother they have not heard. Listening comes before the commit,
                // on this screen, not one screen later.
                val playback by ServiceLocator.playback.state.collectAsStateWithLifecycle()
                val isThisDraft = playback.storyId == DRAFT_PLAYBACK_KEY
                val playable = ServiceLocator.playback.canPlay(finishedPath)
                val positionMs = if (isThisDraft) playback.positionMs else 0L
                val totalMs = if (isThisDraft && playback.durationMs > 0L) {
                    playback.durationMs
                } else {
                    state.elapsedMs
                }

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        IconButton(
                            onClick = {
                                ServiceLocator.playback.toggle(DRAFT_PLAYBACK_KEY, finishedPath)
                            },
                            enabled = playable,
                            modifier = Modifier.size(48.dp)
                        ) {
                            val playing = isThisDraft && playback.isPlaying
                            Icon(
                                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription =
                                    if (playing) "Pause" else "Listen before you save"
                            )
                        }

                        // Seek on release. Seeking per touch sample starts the voice
                        // playing out loud the instant you touch the bar to check the
                        // length, and every seek on an inactive draft builds a
                        // MediaPlayer and calls prepare() on the main thread.
                        var scrub by remember { mutableStateOf<Float?>(null) }
                        Slider(
                            value = scrub
                                ?: if (totalMs > 0L) positionMs.toFloat() / totalMs else 0f,
                            onValueChange = { scrub = it },
                            onValueChangeFinished = {
                                scrub?.let { fraction ->
                                    ServiceLocator.playback.seekTo(
                                        DRAFT_PLAYBACK_KEY,
                                        finishedPath,
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
                }

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Does not reset the bus. Review reads the path and duration from it.
                    OutlinedButton(
                        onClick = {
                            ServiceLocator.playback.stop()
                            onDone()
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Save this story") }

                    // Discarding a take has to be as reachable as keeping one. Without
                    // this the only way out of a recording you do not want is to leave it
                    // sitting on disk, which is the opposite of what this app promises.
                    TextButton(onClick = { confirmDiscard = true }) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}
