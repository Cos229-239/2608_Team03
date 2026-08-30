package com.arv.app.feature.onboarding

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.arv.app.core.di.ServiceLocator
import com.arv.app.core.session.ActiveSession
import kotlinx.coroutines.launch

class OnboardingViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ServiceLocator.storyRepository(app)

    var working by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    /**
     * Creates the archive and opens it. Runs in [viewModelScope], not in a composition
     * scope: rotating the phone mid-write used to kill the coroutine after the person row
     * had been written but before the session was set, leaving an orphan family behind and
     * the first screen of the app with a permanently disabled button and no explanation.
     */
    fun createArchive(familyName: String, yourName: String, onReady: () -> Unit) {
        if (working) return
        working = true
        error = null
        viewModelScope.launch {
            try {
                val created = repo.createFamily(
                    familyName = familyName,
                    ownerDisplayName = yourName,
                    nowMillis = System.currentTimeMillis()
                )
                ActiveSession.set(created.familyId, created.userId, created.familyName)
                // The archive now has exactly one person in it, and that person is the
                // viewer. Without this their own ancestor set stays empty until the next
                // launch, and BRANCH would deny them their own line.
                repo.refreshLineage(created.familyId, created.userId)
                onReady()
            } catch (t: Throwable) {
                error = "Could not create the archive. Nothing was saved. Try again."
            } finally {
                working = false
            }
        }
    }

    /**
     * The sample family, reachable on purpose and not only on a fresh install. It is what
     * the build review runs on, and needing to wipe app data to demo the app would be a
     * design failure.
     */
    fun openSampleFamily() {
        ActiveSession.set(
            familyId = ServiceLocator.DEMO_FAMILY_ID,
            userId = ServiceLocator.DEMO_USER_ID,
            familyName = "Sample family"
        )
    }
}

/**
 * Screen 01. The first thing anyone sees, and the only screen that runs before an archive
 * exists.
 *
 * It asks for two things and no more. Every extra field here is a field asked of someone
 * who has not yet been given a reason to trust the app with anything.
 */
@Composable
fun OnboardingScreen(
    onReady: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OnboardingViewModel = viewModel()
) {
    var familyName by remember { mutableStateOf("") }
    var yourName by remember { mutableStateOf("") }

    val working = viewModel.working
    val canCreate = familyName.isNotBlank() && yourName.isNotBlank() && !working

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            "Arv",
            style = MaterialTheme.typography.displaySmall
        )
        Text(
            "An archive belongs to a family, so this starts by asking which one. " +
                "Nothing leaves this phone.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedTextField(
            value = familyName,
            onValueChange = { familyName = it },
            label = { Text("Family name") },
            placeholder = { Text("The Delaney family") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = yourName,
            onValueChange = { yourName = it },
            label = { Text("Your name") },
            supportingText = { Text("You are the first person in the archive, not an account beside it.") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        viewModel.error?.let { message ->
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }

        Button(
            onClick = { viewModel.createArchive(familyName, yourName, onReady) },
            enabled = canCreate,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
        ) {
            Text(if (working) "Creating" else "Create the archive")
        }

        TextButton(
            onClick = {
                viewModel.openSampleFamily()
                onReady()
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
        ) {
            Text("Look at the sample family instead")
        }
    }
}
