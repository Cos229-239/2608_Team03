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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.arv.app.core.data.StoryRepository
import com.arv.app.core.di.ServiceLocator
import com.arv.app.core.model.MemberRole
import com.arv.app.core.session.ActiveSession
import kotlinx.coroutines.launch

class OnboardingViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ServiceLocator.storyRepository(app)

    var working by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    /**
     * Archives on this phone that the signed-in account already belongs to.
     *
     * Signing out closes the archive and keeps every row of it, and this screen used to
     * offer only a new family afterwards, so signing back in walked straight past the
     * archive that was still here. Empty for a new account, and for the sample family.
     */
    var archives by mutableStateOf<List<StoryRepository.YourArchive>>(emptyList())
        private set

    init {
        ActiveSession.authUid?.let { uid ->
            viewModelScope.launch {
                archives = runCatching { repo.archivesFor(uid) }.getOrDefault(emptyList())
            }
        }
    }

    /** Opens an archive this account already has, exactly as creating or joining one does. */
    fun openArchive(archive: StoryRepository.YourArchive, onReady: () -> Unit) {
        val uid = ActiveSession.authUid ?: return
        if (working) return
        working = true
        error = null
        viewModelScope.launch {
            try {
                ActiveSession.set(archive.familyId, uid, archive.name ?: "Family archive", archive.role)
                // Role and lineage come from the member row, the same as on every launch.
                repo.refreshLineage(archive.familyId, uid)
                onReady()
            } catch (t: Throwable) {
                error = "Could not open that archive. Nothing was changed. Try again."
            } finally {
                working = false
            }
        }
    }

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
                    nowMillis = System.currentTimeMillis(),
                    // The account that just signed in owns the family it creates.
                    userId = ActiveSession.authUid
                )
                ActiveSession.set(created.familyId, created.userId, created.familyName, created.role)
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
            familyName = "Sample family",
            role = MemberRole.OWNER
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
    onJoinWithCode: () -> Unit,
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
                "It stays on this phone unless you invite somebody or turn on sharing.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (viewModel.archives.isNotEmpty()) {
            Text(
                "YOUR ARCHIVES ON THIS PHONE",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            viewModel.archives.forEach { archive ->
                OutlinedButton(
                    onClick = { viewModel.openArchive(archive, onReady) },
                    enabled = !working,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            "Open " + (archive.name ?: "an unnamed archive"),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            describe(archive),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            HorizontalDivider()
            Text(
                "Or start a new one",
                style = MaterialTheme.typography.titleMedium
            )
        }

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

        // The second half of the question. Somebody joining a family that already exists
        // must not have to create a second one to get past this screen, which is what
        // happened for as long as this was the only way through.
        TextButton(
            onClick = onJoinWithCode,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
        ) {
            Text("Somebody gave me a code")
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

/** "Owner. With Ann, Benjamin and Billie." Enough to tell two archives apart. */
private fun describe(archive: StoryRepository.YourArchive): String {
    val role = when (archive.role) {
        MemberRole.OWNER -> "Owner"
        MemberRole.KEEPER -> "Keeper"
        MemberRole.CONTRIBUTOR -> "Contributor"
        MemberRole.VIEWER -> "Viewer"
    }
    val people = archive.somePeople
    val alongside = when (people.size) {
        0 -> ""
        1 -> " With ${people[0]}."
        2 -> " With ${people[0]} and ${people[1]}."
        else -> " With ${people[0]}, ${people[1]} and ${people[2]}."
    }
    return "$role.$alongside"
}
