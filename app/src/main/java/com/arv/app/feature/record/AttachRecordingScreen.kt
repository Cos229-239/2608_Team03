package com.arv.app.feature.record

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.arv.app.core.di.ServiceLocator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * What happens to a recording made from inside a story that already exists.
 *
 * Deliberately not the review screen. Review asks who is speaking, when it happened, and
 * who may hear it, because a new story has nobody to inherit those answers from. This
 * recording does: the story already decided all of it. Asking again would invite two
 * different answers for one memory.
 *
 * So the only questions left are the honest ones. Is this the take you want, and does it
 * belong to this story.
 */
class AttachRecordingViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ServiceLocator.storyRepository(app)
    private val viewer = ServiceLocator.viewer

    data class State(
        val attaching: Boolean = false,
        val attached: Boolean = false,
        val error: String? = null
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    val playback = ServiceLocator.playback

    fun attach(storyId: String, localAudioPath: String, durationMs: Long, nowMillis: Long) {
        if (_state.value.attaching) return
        _state.value = _state.value.copy(attaching = true, error = null)
        viewModelScope.launch {
            val assetId = runCatching {
                repo.addRecordingToStory(storyId, viewer, localAudioPath, durationMs, nowMillis)
            }.getOrNull()

            if (assetId == null) {
                _state.value = State(error = "This recording could not be added to the story.")
                return@launch
            }

            _state.value = State(attached = true)

            // Transcription outlives this screen on purpose. Leaving the page should not
            // cancel the work, the same way it does not when a story is first saved.
            val service = ServiceLocator.transcriptionService(getApplication()) ?: return@launch
            ServiceLocator.appScope.launch {
                runCatching { repo.transcribeStory(storyId, service) }
            }
        }
    }
}

@Composable
fun AttachRecordingScreen(
    storyId: String,
    localAudioPath: String,
    durationMs: Long,
    nowMillis: Long,
    onAttached: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AttachRecordingViewModel = viewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    if (state.attached) {
        LaunchedEffect(Unit) { onAttached() }
    }

    DisposableEffect(Unit) {
        onDispose { ServiceLocator.playback.stop() }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 48.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("Add this recording", style = MaterialTheme.typography.headlineMedium)
        }

        item {
            Text(
                "It joins the story you were just looking at. The title, the year and who " +
                    "can hear it stay exactly as that story already has them.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            Card {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Listen before you add it", style = MaterialTheme.typography.titleMedium)
                    DraftPlayer(localAudioPath = localAudioPath, durationMs = durationMs)
                }
            }
        }

        state.error?.let { message ->
            item {
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = {
                        viewModel.attach(storyId, localAudioPath, durationMs, nowMillis)
                    },
                    enabled = !state.attaching
                ) {
                    Text(if (state.attaching) "Adding" else "Add to this story")
                }
                OutlinedButton(onClick = onCancel, enabled = !state.attaching) {
                    Text("Discard")
                }
            }
        }

        if (state.attaching) {
            item { CircularProgressIndicator() }
        }
    }
}
