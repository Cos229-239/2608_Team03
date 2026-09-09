package com.arv.app.feature.invite

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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.arv.app.core.data.InviteCode
import com.arv.app.core.data.Invitation
import com.arv.app.core.di.ServiceLocator
import com.arv.app.core.session.ActiveSession
import kotlinx.coroutines.launch

/**
 * What the join screen is showing right now.
 *
 * [refusal] and [joined] are separate from [preview] because a refusal has to survive the
 * preview being recomputed. Typing one more character after "already used" should clear
 * the message, but navigating back to it should not.
 */
data class JoinUiState(
    val typed: String = "",
    /** The archive the typed code opens, once it is a code we recognise. */
    val preview: JoinPreview? = null,
    val refusal: String? = null,
    val working: Boolean = false,
    val joined: Boolean = false,
    /** Set after joining when the archive turned out to be empty on this phone. */
    val emptyOnThisPhone: Boolean = false
)

/** What a person is being asked to agree to, before they agree to it. */
data class JoinPreview(val familyName: String?, val roleLabel: String)

class JoinFamilyViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ServiceLocator.storyRepository(app)

    var state by mutableStateOf(JoinUiState())
        private set

    /**
     * Looks the code up as it is typed, so the screen can name the family before anyone
     * commits to it.
     *
     * A code read down a phone line is agreed to on the strength of who read it out. The
     * least this screen can do is say what accepting it puts them in, rather than asking
     * for a yes and revealing the answer afterwards.
     */
    fun onTyped(input: String) {
        state = state.copy(typed = input, refusal = null)
        val normalized = InviteCode.normalize(input)
        if (normalized == null) {
            state = state.copy(preview = null)
            return
        }
        viewModelScope.launch {
            val invite = repo.previewInvite(input)
            // Only a live code previews. A spent or revoked one has nothing to offer and
            // saying which family it used to open would leak the name to anyone guessing.
            val live = invite?.takeIf { it.usedAt == null && it.revokedAt == null }
            state = state.copy(
                preview = live?.let {
                    JoinPreview(
                        familyName = it.familyName,
                        roleLabel = roleLabel(it.grantsRole.name)
                    )
                }
            )
        }
    }

    fun join() {
        if (state.working) return
        val userId = ActiveSession.authUid
        if (userId == null) {
            state = state.copy(refusal = "Sign in first. A standing in a family belongs to an account.")
            return
        }
        state = state.copy(working = true, refusal = null)
        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                when (val result = repo.redeemInvite(state.typed, userId, now)) {
                    is Invitation.Result.Accepted -> {
                        val familyId = result.member.familyId
                        ActiveSession.set(
                            familyId = familyId,
                            userId = userId,
                            familyName = result.spent.familyName ?: "Family archive",
                            role = result.member.role
                        )
                        // The joiner has a standing and no profile yet, so this settles
                        // their role and leaves their lineage empty, which makes branch
                        // material fail closed until somebody places them in the tree.
                        repo.refreshLineage(familyId, userId, now)
                        state = state.copy(
                            working = false,
                            joined = true,
                            emptyOnThisPhone = repo.archiveWeight(familyId) == 0
                        )
                    }
                    else -> state = state.copy(working = false, refusal = refusalText(result))
                }
            } catch (t: Throwable) {
                state = state.copy(
                    working = false,
                    refusal = "Could not join. Nothing was changed. Try again."
                )
            }
        }
    }

    private fun roleLabel(role: String): String = when (role) {
        "KEEPER" -> "a keeper, who can correct the family's records"
        "CONTRIBUTOR" -> "a contributor, who can add recordings and people"
        else -> "a viewer, who can read what the family shows everyone"
    }

    /**
     * One sentence per refusal, and never the word invalid.
     *
     * Each of these tells the person what to do next, which is the whole reason
     * [Invitation.Result] has seven answers rather than a boolean.
     */
    private fun refusalText(result: Invitation.Result): String = when (result) {
        is Invitation.Result.NotACode ->
            "That is not a complete code yet. They are six characters, like K7M-2QX."
        is Invitation.Result.Unknown ->
            "No invitation matches that code. Check it against what they read out, character by character."
        is Invitation.Result.AlreadyUsed ->
            "That code has already been used. Ask them for a new one; each code admits one person."
        is Invitation.Result.Revoked ->
            "That invitation was withdrawn. Ask them for a new code."
        is Invitation.Result.YourOwn ->
            "That is your own invitation. Nobody invites themselves into a family."
        is Invitation.Result.AlreadyInThisFamily ->
            "You are already in this family. Nothing to do."
        is Invitation.Result.Accepted -> ""
    }
}

/**
 * The other half of onboarding. Creating an archive was always here; joining one was not,
 * so a code could be minted and read out and there was no screen that would take it.
 */
@Composable
fun JoinFamilyScreen(
    onJoined: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: JoinFamilyViewModel = viewModel()
) {
    val state = viewModel.state

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Join a family", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Somebody in the family gives you a code. It works once, and it records that " +
                "they are the one who let you in.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedTextField(
            value = state.typed,
            onValueChange = viewModel::onTyped,
            label = { Text("Invitation code") },
            placeholder = { Text("K7M-2QX") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                imeAction = ImeAction.Done
            ),
            supportingText = {
                Text("Dashes and capitals do not matter. There is no O, I, L, 0 or 1 in a code.")
            },
            modifier = Modifier.fillMaxWidth()
        )

        state.preview?.let { preview ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        preview.familyName ?: "A family archive",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "You would join as " + preview.roleLabel + ".",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    // Said out loud rather than hidden, because the alternative is somebody
                    // agreeing to join something the screen could not name.
                    if (preview.familyName == null) {
                        Text(
                            "This code was made before invitations carried the family's " +
                                "name, so the archive cannot tell you what it is called.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        state.refusal?.let { message ->
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }

        if (state.joined) {
            Text(
                if (state.emptyOnThisPhone) {
                    "You are in. This phone does not hold the family's recordings yet, " +
                        "because nothing syncs between phones so far. What you record here " +
                        "is yours and it stays here."
                } else {
                    "You are in."
                },
                style = MaterialTheme.typography.bodyMedium
            )
            Button(
                onClick = onJoined,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
            ) { Text("Open the archive") }
        } else {
            Button(
                onClick = viewModel::join,
                enabled = InviteCode.isValid(state.typed) && !state.working,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
            ) { Text(if (state.working) "Joining" else "Join") }

            TextButton(
                onClick = onBack,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
            ) { Text("I do not have a code") }
        }
    }
}
