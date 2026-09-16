package com.arv.app.feature.invite

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.arv.app.core.data.InviteService
import com.arv.app.core.data.local.MemberEntity
import com.arv.app.core.di.ServiceLocator
import com.arv.app.core.model.MemberRole
import com.arv.app.core.session.ActiveSession
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MembersViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ServiceLocator.storyRepository(app)
    private val invites = ServiceLocator.inviteService(app)
    private val familyId = ServiceLocator.familyId

    /** The account reading this screen. */
    val me: String = ServiceLocator.userId

    /** Everyone else standing in the family. The owner reading this is left out of it. */
    val others: StateFlow<List<MemberEntity>> =
        repo.observeMembers(familyId)
            .map { rows -> rows.filter { it.userId != me } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var working by mutableStateOf(false)
        private set

    /** What the last removal came to. Left on screen until the next one. */
    var result by mutableStateOf<InviteService.Removal?>(null)
        private set

    /** Something failed on this phone itself, not on the server. Said plainly, not relabelled. */
    var brokeHere by mutableStateOf(false)
        private set

    fun remove(target: MemberEntity) {
        if (working) return
        working = true
        brokeHere = false
        viewModelScope.launch {
            val outcome = runCatching { invites.removeMember(familyId, me, target.userId) }
            result = outcome.getOrNull()
            brokeHere = outcome.isFailure
            working = false
        }
    }
}

/**
 * Who else stands in this family, and the one way to take somebody out of it.
 *
 * Owner only, because the rules accept a removal from the owner and from nobody else. The
 * server holds no name for a member, only an account, so each row says what the archive
 * does know: their role, who let them in, and when.
 */
@Composable
fun MembersSection(
    modifier: Modifier = Modifier,
    viewModel: MembersViewModel = viewModel()
) {
    if (ActiveSession.role != MemberRole.OWNER) return

    val others by viewModel.others.collectAsStateWithLifecycle()
    var confirming by remember { mutableStateOf<MemberEntity?>(null) }

    confirming?.let { target ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text("Remove this person?") },
            text = {
                Text(
                    "They lose their place in this family on every phone, and the invitation " +
                        "that let them in stays on record. Anything already saved on their own " +
                        "phone stays there, because nothing in this app reaches into somebody " +
                        "else's phone."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.remove(target)
                        confirming = null
                    }
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { confirming = null }) { Text("Keep them") }
            }
        )
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (others.isEmpty()) {
            Text(
                "Nobody else is in this family yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        others.forEach { member ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(roleName(member.role), style = MaterialTheme.typography.titleMedium)
                        Text(
                            invitedBy(member.invitedBy, viewModel.me) + ", joined " + dayOf(member.joinedAt),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "Account ending " + member.userId.takeLast(6),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(
                        onClick = { confirming = member },
                        enabled = !viewModel.working,
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text("Remove") }
                }
            }
        }

        when (val done = viewModel.result) {
            is InviteService.Removal.Removed -> Text(
                if (done.everywhere) "Removed from this family on every phone."
                else "Removed from this phone. This build has no server, so no other phone was told.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            InviteService.Removal.CouldNotReach -> Text(
                "Could not reach the family's server, so nobody was removed anywhere. " +
                    "Try again when you are online.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
            InviteService.Removal.NotAllowed -> Text(
                "Only the owner can remove somebody, and the owner cannot be removed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
            InviteService.Removal.NotAMember -> Text(
                "That person is no longer in this family.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            null -> Unit
        }

        if (viewModel.brokeHere) {
            Text(
                "Something went wrong on this phone. Check the list above before trying again.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

private fun roleName(role: MemberRole): String = when (role) {
    MemberRole.OWNER -> "Owner"
    MemberRole.KEEPER -> "Keeper"
    MemberRole.CONTRIBUTOR -> "Contributor"
    MemberRole.VIEWER -> "Viewer"
}

private fun invitedBy(inviter: String?, me: String): String = when (inviter) {
    null -> "Invited before invitations were recorded"
    me -> "Invited by you"
    else -> "Invited by another member"
}

private fun dayOf(millis: Long): String =
    SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(millis))
