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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
                    state.isPaused -> "Paused"
                    state.isRecording -> "Recording. The screen can turn off."
                    else -> "Ready"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.error != null) {
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
                            RecordingService.send(context, RecordingService.ACTION_START)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(64.dp)
                    ) { Text(stringResource(R.string.record_start)) }
                }
            }

            // TODO(CAP-5): route to Review & Save (screen 05) instead of closing.
            if (!state.isRecording && state.outputPath != null) {
                // Does not reset the bus. Review reads the path and duration from it.
                OutlinedButton(
                    onClick = onDone,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Save this story") }
            }
        }
    }
}
