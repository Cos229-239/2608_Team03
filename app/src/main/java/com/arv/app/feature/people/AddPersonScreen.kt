package com.arv.app.feature.people

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.arv.app.core.di.ServiceLocator
import kotlinx.coroutines.launch
import java.util.Calendar

class AddPersonViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ServiceLocator.storyRepository(app)

    suspend fun add(
        displayName: String,
        relationLabel: String,
        birthYear: Int?,
        deathYear: Int?,
        birthPlace: String
    ): String = repo.addPerson(
        familyId = ServiceLocator.familyId,
        displayName = displayName,
        relationLabel = relationLabel,
        birthYear = birthYear,
        deathYear = deathYear,
        birthPlace = birthPlace,
        nowMillis = System.currentTimeMillis()
    )
}

/**
 * Adding a relative by hand.
 *
 * Only the name is required. Families researching their own line often have a name and
 * nothing else for years, and a form that demands dates would turn "I know she existed"
 * into something the archive refuses to hold.
 */
@Composable
fun AddPersonScreen(
    onSaved: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AddPersonViewModel = viewModel()
) {
    var displayName by remember { mutableStateOf("") }
    var relationLabel by remember { mutableStateOf("") }
    var birthYear by remember { mutableStateOf("") }
    var deathYear by remember { mutableStateOf("") }
    var birthPlace by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val thisYear = remember { Calendar.getInstance().get(Calendar.YEAR) }
    val birth = birthYear.toIntOrNull()
    val death = deathYear.toIntOrNull()

    val birthError = when {
        birthYear.isBlank() -> null
        birth == null -> "Years only, like 1936"
        birth > thisYear -> "That year has not happened yet"
        else -> null
    }
    val deathError = when {
        deathYear.isBlank() -> null
        death == null -> "Years only, like 1936"
        death > thisYear -> "That year has not happened yet"
        birth != null && death < birth -> "Before the birth year"
        else -> null
    }

    val canSave = displayName.isNotBlank() && birthError == null && deathError == null && !working

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Add someone", style = MaterialTheme.typography.headlineSmall)

        OutlinedTextField(
            value = displayName,
            onValueChange = { displayName = it },
            label = { Text("Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = relationLabel,
            onValueChange = { relationLabel = it },
            label = { Text("Relation (optional)") },
            placeholder = { Text("Grandmother") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = birthYear,
                onValueChange = { birthYear = it.filter(Char::isDigit).take(4) },
                label = { Text("Born") },
                isError = birthError != null,
                supportingText = birthError?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = deathYear,
                onValueChange = { deathYear = it.filter(Char::isDigit).take(4) },
                label = { Text("Died") },
                isError = deathError != null,
                supportingText = deathError?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }

        OutlinedTextField(
            value = birthPlace,
            onValueChange = { birthPlace = it },
            label = { Text("Born where (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Text(
            "A death year moves this profile to a memorial, which changes who may add to " +
                "it. Leave it empty for anyone living.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Button(
            onClick = {
                working = true
                scope.launch {
                    val id = viewModel.add(displayName, relationLabel, birth, death, birthPlace)
                    onSaved(id)
                }
            },
            enabled = canSave,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
        ) {
            Text("Add to the archive")
        }
    }
}
