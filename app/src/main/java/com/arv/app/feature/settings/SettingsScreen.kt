package com.arv.app.feature.settings

import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.arv.app.core.data.ArchiveExport
import com.arv.app.core.data.FamilyImport
import com.arv.app.core.di.ServiceLocator
import com.arv.app.core.session.ActiveSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the app can currently do about turning a recording into text. */
sealed interface SpeechModelState {
    data object Missing : SpeechModelState
    data class Downloading(val progress: Float) : SpeechModelState
    data object Ready : SpeechModelState
    data class Failed(val message: String) : SpeechModelState
}

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val store = ServiceLocator.voskModelStore(app)

    private val _model = MutableStateFlow<SpeechModelState>(
        if (store.isReady) SpeechModelState.Ready else SpeechModelState.Missing
    )
    val model: StateFlow<SpeechModelState> = _model.asStateFlow()

    val familyName: String get() = ActiveSession.familyName ?: "This archive"
    val downloadMb: Int get() = (store.downloadBytes / 1_000_000).toInt()

    fun download() {
        if (_model.value is SpeechModelState.Downloading) return
        _model.value = SpeechModelState.Downloading(0f)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                store.ensure { p -> _model.value = SpeechModelState.Downloading(p) }
            }
            _model.value = result.fold(
                onSuccess = {
                    // The recordings made while there was no model have been waiting for
                    // this exact moment. Fire and forget on the app scope: leaving
                    // Settings must not cancel their transcription.
                    ServiceLocator.transcriptionService(getApplication())?.let { service ->
                        ServiceLocator.appScope.launch {
                            runCatching {
                                repo.transcribeAwaiting(ServiceLocator.familyId, service)
                            }
                        }
                    }
                    SpeechModelState.Ready
                },
                onFailure = {
                    SpeechModelState.Failed(
                        "Could not download the speech model. Check the connection and try again."
                    )
                }
            )
        }
    }

    private val repo = ServiceLocator.storyRepository(app)

    var importResult by mutableStateOf<String?>(null)
        private set

    /**
     * Reads a family history file in, without upgrading anything on the way.
     *
     * Reports what it did in the same breath, including how many people arrived unlinked
     * and how many are held on somebody's word alone, because those two numbers are the
     * actual state of a family's record and hiding them is how a tree turns into fiction.
     */
    fun importFamily(uri: android.net.Uri) {
        importResult = null
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val text = getApplication<Application>().contentResolver
                        .openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: error("Could not open that file")
                    val parsed = FamilyImport.parse(text).getOrThrow()
                    val n = repo.importFamily(
                        familyId = ServiceLocator.familyId,
                        userId = ServiceLocator.userId,
                        parsed = parsed,
                        nowMillis = System.currentTimeMillis()
                    )
                    Triple(n, parsed.unlinked, parsed.needingChecks)
                }
            }
            importResult = outcome.fold(
                onSuccess = { (n, unlinked, unchecked) ->
                    "Imported $n people. $unlinked still need placing in the tree, " +
                        "$unchecked are recorded on somebody's word alone."
                },
                onFailure = { "Could not read that file as a family history." }
            )
        }
    }

    var exportResult by mutableStateOf<String?>(null)
        private set

    /**
     * Writes the archive out to a file the family keeps.
     *
     * The app deliberately never uploads anything and deliberately never wipes a database
     * to survive a schema change. Both are right, and together they mean the only copy of
     * somebody's voice sits in one folder on one phone. This is the way out of that.
     */
    fun export(uri: android.net.Uri) {
        exportResult = "Writing"
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val app = getApplication<Application>()
                    app.contentResolver.openOutputStream(uri)?.use { out ->
                        ArchiveExport.writeTo(
                            out = out,
                            db = com.arv.app.core.data.local.ArvDatabase.get(app),
                            familyId = ServiceLocator.familyId,
                            familyName = familyName,
                            filesDir = app.filesDir,
                            viewer = ServiceLocator.viewer
                        )
                    } ?: error("Could not write there")
                }
            }
            exportResult = outcome.fold(
                onSuccess = {
                    "Archive written. It contains the recordings, the records, and a page " +
                        "that opens in any browser without this app."
                },
                onFailure = { "Could not write the archive there." }
            )
        }
    }

    fun removeModel() {
        store.clear()
        _model.value = SpeechModelState.Missing
    }

    /**
     * Forgets which archive is open. Deletes nothing.
     *
     * Worth being exact about, because the words "sign out" make people expect loss. Every
     * story, person and recording stays in Room untouched; this only clears the pointer to
     * which family the app is showing. Deleting an archive would be a separate, deliberate
     * act and does not belong behind this button.
     */
    fun signOut() {
        ActiveSession.clear()
    }
}

/**
 * Screen 20-ish. The room where the app admits what it is and is not currently able to do.
 *
 * It exists because two things had no home. Speech recognition needs a model on the phone
 * and someone has to be able to put it there, and until now the app had no way out of an
 * archive once you opened one: tapping "sample family" a single time put you in the demo
 * permanently, across relaunches, with clearing app data as the only exit.
 */
@Composable
fun SettingsScreen(
    onSignedOut: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel()
) {
    val model by viewModel.model.collectAsStateWithLifecycle()
    var confirmSignOut by remember { mutableStateOf(false) }

    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri -> uri?.let(viewModel::export) }

    val importPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::importFamily) }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Leave this archive?") },
            text = {
                Text(
                    "Nothing is deleted. Every story and recording stays on this phone. " +
                        "This only closes the archive so you can open a different one."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.signOut()
                        confirmSignOut = false
                        onSignedOut()
                    }
                ) { Text("Leave") }
            },
            dismissButton = {
                TextButton(onClick = { confirmSignOut = false }) { Text("Stay") }
            }
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 48.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { Text("Settings", style = MaterialTheme.typography.headlineMedium) }

        item {
            Text(
                "Open archive: ${viewModel.familyName}",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item { HorizontalDivider() }

        item { SectionLabel("Turning recordings into text") }

        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    when (val m = model) {
                        is SpeechModelState.Ready -> {
                            Text(
                                "Ready",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                "Recordings are transcribed on this phone. The audio is " +
                                    "never sent anywhere, and this works with the phone " +
                                    "in airplane mode.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedButton(onClick = viewModel::removeModel) {
                                Text("Remove the speech files")
                            }
                        }

                        is SpeechModelState.Downloading -> {
                            Text(
                                "Setting up, ${(m.progress * 100).toInt()}%",
                                style = MaterialTheme.typography.titleMedium
                            )
                            LinearProgressIndicator(
                                progress = { m.progress },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                "This happens once. You can leave this screen.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        is SpeechModelState.Missing, is SpeechModelState.Failed -> {
                            Text(
                                "Not set up yet",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                "Until this is done, recordings are saved and playable but " +
                                    "not turned into searchable text. It is a one-time " +
                                    "download of about ${viewModel.downloadMb} MB, and it " +
                                    "is the speech files themselves, not your recordings. " +
                                    "Nothing you record is ever uploaded.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (m is SpeechModelState.Failed) {
                                Text(
                                    m.message,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            Button(
                                onClick = viewModel::download,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 52.dp)
                            ) {
                                Text(
                                    if (m is SpeechModelState.Failed) "Try again"
                                    else "Set up transcription"
                                )
                            }
                        }
                    }
                }
            }
        }

        item { HorizontalDivider() }

        item { SectionLabel("Keeping a copy") }

        item {
            Text(
                "Write the whole archive to a file: the recordings, the records, the " +
                    "people, and a page that opens in any browser. Nothing here is " +
                    "uploaded anywhere, which also means nothing here survives losing " +
                    "this phone unless you keep a copy.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            OutlinedButton(
                onClick = { exportPicker.launch("arv-archive.zip") },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
            ) { Text("Save a copy of everything") }
        }

        viewModel.exportResult?.let { message ->
            item {
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        item { HorizontalDivider() }

        item { SectionLabel("Family history") }

        item {
            Text(
                "Import a family history file. Nothing gets promoted on the way in: " +
                    "people arrive with whatever certainty the file claims for them, and " +
                    "the ones nobody has checked are listed so they can be.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            OutlinedButton(
                onClick = { importPicker.launch(arrayOf("application/json", "text/*")) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
            ) { Text("Import family history") }
        }

        viewModel.importResult?.let { message ->
            item {
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        item { HorizontalDivider() }

        item { SectionLabel("This archive") }

        item {
            OutlinedButton(
                onClick = { confirmSignOut = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
            ) {
                Text("Leave this archive")
            }
        }

        item {
            Text(
                "Leaving does not delete anything. It closes the archive so a different " +
                    "one can be opened on this phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
