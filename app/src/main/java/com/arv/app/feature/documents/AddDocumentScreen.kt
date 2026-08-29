package com.arv.app.feature.documents

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.arv.app.core.di.ServiceLocator
import com.arv.app.core.model.AiUsePolicy
import com.arv.app.core.model.ArchiveArea
import com.arv.app.core.model.EraPrecision
import com.arv.app.core.model.Person
import com.arv.app.core.model.Visibility
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class AddDocumentUiState(
    /** Where the picked file now lives inside app storage. Null until one is chosen. */
    val localPath: String? = null,
    val mimeType: String = "",
    val displayName: String = "",
    val title: String = "",
    val eraText: String = "",
    val eraUnknown: Boolean = false,
    val place: String = "",
    val tagText: String = "",
    val subjectPersonIds: List<String> = emptyList(),
    val visibility: Visibility = Visibility.FAMILY,
    val area: ArchiveArea = ArchiveArea.LINEAGE,
    val copying: Boolean = false,
    val saving: Boolean = false,
    val savedStoryId: String? = null,
    val error: String? = null
) {
    val isImage: Boolean get() = mimeType.startsWith("image/")
    val canSave: Boolean get() = localPath != null && !saving && !copying
}

class AddDocumentViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ServiceLocator.storyRepository(app)
    private val familyId = ServiceLocator.familyId

    private val _state = MutableStateFlow(AddDocumentUiState())
    val state: StateFlow<AddDocumentUiState> = _state.asStateFlow()

    val people: StateFlow<List<Person>> = repo.observePeople(familyId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun onTitle(v: String) { _state.value = _state.value.copy(title = v) }
    fun onEra(v: String) { _state.value = _state.value.copy(eraText = v, eraUnknown = false) }
    fun onPlace(v: String) { _state.value = _state.value.copy(place = v) }
    fun onTags(v: String) { _state.value = _state.value.copy(tagText = v) }
    fun onVisibility(v: Visibility) { _state.value = _state.value.copy(visibility = v) }
    fun onArea(v: ArchiveArea) { _state.value = _state.value.copy(area = v) }

    fun toggleEraUnknown() {
        val s = _state.value
        _state.value = s.copy(
            eraUnknown = !s.eraUnknown,
            eraText = if (!s.eraUnknown) "" else s.eraText
        )
    }

    fun toggleSubject(personId: String) {
        val current = _state.value.subjectPersonIds
        _state.value = _state.value.copy(
            subjectPersonIds = if (personId in current) current - personId else current + personId
        )
    }

    /**
     * Copies the picked file into app storage before anything else happens.
     *
     * A content:// URI is a temporary grant. It can be revoked, and the file behind it can
     * be moved or deleted by whatever app owns it. Storing the URI would mean the archive
     * quietly loses a marriage certificate the next time someone cleans out their gallery,
     * so the bytes are taken now and the archive owns its own copy.
     */
    fun onPicked(uri: Uri) {
        _state.value = _state.value.copy(copying = true, error = null)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val resolver = getApplication<Application>().contentResolver
                    val mime = resolver.getType(uri) ?: "application/octet-stream"
                    val name = displayNameOf(uri) ?: "document"

                    val dir = File(getApplication<Application>().filesDir, "documents")
                        .apply { mkdirs() }
                    val ext = name.substringAfterLast('.', "").ifBlank {
                        when {
                            mime.startsWith("image/") -> mime.substringAfter('/')
                            mime == "application/pdf" -> "pdf"
                            else -> "bin"
                        }
                    }
                    val target = File(dir, "${UUID.randomUUID()}.$ext")

                    resolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    } ?: error("Could not open the selected file")

                    if (target.length() == 0L) {
                        target.delete()
                        error("The selected file was empty")
                    }
                    Triple(target.absolutePath, mime, name)
                }
            }

            result.fold(
                onSuccess = { (path, mime, name) ->
                    _state.value = _state.value.copy(
                        localPath = path,
                        mimeType = mime,
                        displayName = name,
                        title = _state.value.title.ifBlank { name.substringBeforeLast('.') },
                        copying = false
                    )
                },
                onFailure = {
                    _state.value = _state.value.copy(
                        copying = false,
                        error = "Could not read that file. Try picking it again."
                    )
                }
            )
        }
    }

    private fun displayNameOf(uri: Uri): String? = runCatching {
        getApplication<Application>().contentResolver
            .query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) c.getString(i) else null
            }
    }.getOrNull()

    /** Same forgiving parse the recording flow uses. Unreadable becomes UNKNOWN, not a guess. */
    private fun parseEra(text: String): Triple<Int?, Int?, EraPrecision> {
        val years = Regex("\\d{4}").findAll(text).map { it.value.toInt() }.toList()
        return when {
            years.isEmpty() -> Triple(null, null, EraPrecision.UNKNOWN)
            years.size == 1 -> Triple(years[0], years[0], EraPrecision.EXACT)
            else -> Triple(years.min(), years.max(), EraPrecision.RANGE)
        }
    }

    fun save(nowMillis: Long) {
        val s = _state.value
        val path = s.localPath ?: return
        if (s.saving) return
        _state.value = s.copy(saving = true, error = null)

        val (start, end, precision) =
            if (s.eraUnknown) Triple(null, null, EraPrecision.UNKNOWN) else parseEra(s.eraText)

        viewModelScope.launch {
            try {
                val id = repo.saveDocument(
                    familyId = familyId,
                    createdByUserId = ServiceLocator.userId,
                    localPath = path,
                    mimeType = s.mimeType,
                    title = s.title,
                    subjectPersonIds = s.subjectPersonIds,
                    eraStart = start,
                    eraEnd = end,
                    eraPrecision = precision,
                    placeLabel = s.place,
                    tags = s.tagText.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                    visibility = s.visibility,
                    aiUsePolicy = AiUsePolicy.SUMMARY_OK,
                    area = s.area,
                    now = nowMillis
                )
                _state.value = _state.value.copy(saving = false, savedStoryId = id)
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    saving = false,
                    error = "Could not save this record. The file is still on this phone."
                )
            }
        }
    }
}

/**
 * Adding the part of an inheritance that is not a voice.
 *
 * Deliberately the same shape as saving a recording: pick the thing, then say who is in it
 * and roughly when, while the person who knows is still standing there. The metadata is the
 * archive; a folder of unlabelled scans is what families already have.
 */
@Composable
fun AddDocumentScreen(
    onSaved: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AddDocumentViewModel = viewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val people by viewModel.people.collectAsStateWithLifecycle()

    state.savedStoryId?.let { id ->
        LaunchedEffect(id) { onSaved(id) }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::onPicked) }

    // Photographs and PDFs are what families actually have. Anything else is accepted too,
    // because a .txt of someone's recipe is still an inheritance.
    val mimeFilter = arrayOf("image/*", "application/pdf", "text/*")

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 48.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { Text("Add a record", style = MaterialTheme.typography.headlineMedium) }

        item {
            Text(
                "A certificate, a photograph, a letter. It is copied into the archive, so " +
                    "it stays even if the original moves.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            if (state.localPath == null) {
                Button(
                    onClick = { picker.launch(mimeFilter) },
                    enabled = !state.copying,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                ) {
                    Text(if (state.copying) "Copying" else "Choose a file")
                }
            } else {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (state.isImage) {
                            AsyncImage(
                                model = state.localPath,
                                contentDescription = "The document you chose",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(220.dp)
                            )
                        }
                        Text(state.displayName, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Copied into the archive",
                            style = MaterialTheme.typography.bodySmall,
                            fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedButton(onClick = { picker.launch(mimeFilter) }) {
                            Text("Choose a different file")
                        }
                    }
                }
            }
        }

        item {
            OutlinedTextField(
                value = state.title,
                onValueChange = viewModel::onTitle,
                label = { Text("What is it") },
                placeholder = { Text("Marriage certificate, Ruth and Ray") },
                modifier = Modifier.fillMaxWidth()
            )
        }

        item { SectionLabel("Which archive") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AreaChip(state, viewModel, ArchiveArea.LINEAGE, "Lineage")
                AreaChip(state, viewModel, ArchiveArea.CULTURE, "Culture")
                AreaChip(state, viewModel, ArchiveArea.STORIES, "Stories")
            }
        }

        item { SectionLabel("Who is in it") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                people.forEach { person ->
                    FilterChip(
                        selected = person.personId in state.subjectPersonIds,
                        onClick = { viewModel.toggleSubject(person.personId) },
                        label = { Text(person.displayName) }
                    )
                }
            }
        }

        item { SectionLabel("When is it from") }
        item {
            OutlinedTextField(
                value = state.eraText,
                onValueChange = viewModel::onEra,
                enabled = !state.eraUnknown,
                label = { Text("Year or range") },
                placeholder = { Text("1961") },
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            FilterChip(
                selected = state.eraUnknown,
                onClick = viewModel::toggleEraUnknown,
                label = { Text("I'm not sure") }
            )
        }

        item {
            OutlinedTextField(
                value = state.place,
                onValueChange = viewModel::onPlace,
                label = { Text("Where") },
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            OutlinedTextField(
                value = state.tagText,
                onValueChange = viewModel::onTags,
                label = { Text("Tags, comma separated") },
                modifier = Modifier.fillMaxWidth()
            )
        }

        item { HorizontalDivider() }

        item { SectionLabel("Who can see this") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VisChip(state, viewModel, Visibility.FAMILY, "Whole family")
                VisChip(state, viewModel, Visibility.PRIVATE, "Only me")
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
            Button(
                onClick = { viewModel.save(System.currentTimeMillis()) },
                enabled = state.canSave,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
            ) {
                Text(if (state.saving) "Saving" else "Add to the archive")
            }
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

@Composable
private fun AreaChip(
    state: AddDocumentUiState,
    viewModel: AddDocumentViewModel,
    area: ArchiveArea,
    label: String
) {
    FilterChip(
        selected = state.area == area,
        onClick = { viewModel.onArea(area) },
        label = { Text(label) }
    )
}

@Composable
private fun VisChip(
    state: AddDocumentUiState,
    viewModel: AddDocumentViewModel,
    visibility: Visibility,
    label: String
) {
    FilterChip(
        selected = state.visibility == visibility,
        onClick = { viewModel.onVisibility(visibility) },
        label = { Text(label) }
    )
}
