package com.arv.app.feature.invite

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.arv.app.core.data.InviteCode
import com.arv.app.core.data.InviteService
import com.arv.app.core.data.local.InviteEntity
import com.arv.app.core.di.ServiceLocator
import com.arv.app.core.model.MemberRole
import com.arv.app.core.session.ActiveSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class InviteViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ServiceLocator.storyRepository(app)
    private val invites = ServiceLocator.inviteService(app)
    private val familyId = ServiceLocator.familyId
    private val userId = ServiceLocator.userId

    private val _code = MutableStateFlow<InviteEntity?>(null)
    val code: StateFlow<InviteEntity?> = _code.asStateFlow()

    var working by mutableStateOf(false)
        private set

    /** How far the code on screen will travel. Null until the first publish has been tried. */
    var reach by mutableStateOf<InviteService.Reach?>(null)
        private set

    /** The code this one took over from, when the server said that one was finished. */
    var replaced by mutableStateOf<InviteEntity?>(null)
        private set

    /** Everything this account has issued here, so the trail is visible to whoever made it. */
    val issued: StateFlow<List<InviteEntity>> =
        repo.observeInvitesIssuedBy(familyId, userId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Minted the first time somebody opens this section, not at sign-up.
     *
     * A code that exists before anyone intends to invite anybody is a live credential
     * nobody decided to create.
     */
    fun ensureCode() {
        if (working || _code.value != null) return
        working = true
        viewModelScope.launch {
            runCatching {
                invites.ensureCode(
                    familyId = familyId,
                    userId = userId,
                    familyName = ActiveSession.familyName,
                    nowMillis = System.currentTimeMillis()
                )
            }.getOrNull()?.let { minted ->
                _code.value = minted.invite
                reach = minted.reach
                replaced = minted.replaced
            }
            working = false
        }
    }

    /**
     * Retires the live code and mints a new one. With a [role], the new code grants that
     * role instead; a code's role is fixed when it is minted, so changing it is a replace.
     */
    fun replaceCode(role: MemberRole = _code.value?.grantsRole ?: MemberRole.CONTRIBUTOR) {
        if (working) return
        working = true
        viewModelScope.launch {
            runCatching {
                invites.replaceCode(
                    familyId = familyId,
                    userId = userId,
                    familyName = ActiveSession.familyName,
                    nowMillis = System.currentTimeMillis(),
                    grantsRole = role
                )
            }.getOrNull()?.let { minted ->
                _code.value = minted.invite
                reach = minted.reach
                replaced = null
            }
            working = false
        }
    }
}

/**
 * The inviting half of the invitation, for whoever already has an archive open.
 *
 * Shown to the owner and keepers only, which is who the server lets mint a code. A
 * contributor used to see this section too, and every code it made was refused on the
 * server and reported as the server being unreachable.
 */
@Composable
fun InviteSection(
    modifier: Modifier = Modifier,
    viewModel: InviteViewModel = viewModel()
) {
    val myRole = ServiceLocator.viewer.role
    if (myRole != MemberRole.OWNER && myRole != MemberRole.KEEPER) return

    val code by viewModel.code.collectAsStateWithLifecycle()
    val issued by viewModel.issued.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.ensureCode() }

    val admitted = issued.filter { it.usedAt != null }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            "Read this out to one person. It works once, it stops working after two " +
                "weeks, and the archive records that you are the one who let them in.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    code?.let { InviteCode.format(it.code) } ?: "Making one",
                    style = MaterialTheme.typography.headlineMedium
                )
                // What the next person becomes. Picking a different one mints a new code,
                // because a code's role is set when it is minted and the rules hold it there.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GRANTABLE.forEach { role ->
                        FilterChip(
                            selected = code?.grantsRole == role,
                            onClick = {
                                if (code?.grantsRole != role) {
                                    copied = false
                                    viewModel.replaceCode(role)
                                }
                            },
                            enabled = !viewModel.working && code != null,
                            label = { Text(roleName(role)) }
                        )
                    }
                }
                Text(
                    roleSentence(code?.grantsRole ?: MemberRole.CONTRIBUTOR),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // Said here rather than left to the join screen, because the person who
                // has to act on it is the one reading the code out, not the one typing it.
                code?.expiresAt?.let { ends ->
                    Text(
                        "Good until " + dayOf(ends) + ".",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // Whether it will work on the other person's phone is the one thing the
                // person reading it out cannot see for themselves.
                when (viewModel.reach) {
                    InviteService.Reach.OtherPhones -> Text(
                        "Works on any phone with the app.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    InviteService.Reach.ThisPhoneOnly -> Text(
                        "Could not reach the family's server, so for now this code only " +
                            "works on this phone. Open this screen again when you are online.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    else -> Unit
                }
                // A code that changed with no explanation reads as a fault. This one changed
                // because the last one did its job, or was pulled, on another phone.
                viewModel.replaced?.let { old ->
                    Text(
                        if (old.usedAt != null) {
                            "Your last code, " + InviteCode.format(old.code) + ", was used on " +
                                dayOf(old.usedAt) + ". This is a new one."
                        } else {
                            "Your last code, " + InviteCode.format(old.code) +
                                ", was withdrawn. This is a new one."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        code?.let { live ->
            TextButton(
                onClick = {
                    clipboard.setText(AnnotatedString(InviteCode.format(live.code)))
                    copied = true
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
            ) { Text(if (copied) "Copied" else "Copy the code") }

            OutlinedButton(
                onClick = {
                    copied = false
                    viewModel.replaceCode()
                },
                enabled = !viewModel.working,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
            ) { Text("Replace this code") }

            Text(
                "Replacing it retires the old one. Do that if it went to the wrong person.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // The trail, shown to the person who made it. Spent rows are kept rather than
        // deleted precisely so this can be answered later.
        if (admitted.isNotEmpty()) {
            Text(
                "You have let " + admitted.size + (if (admitted.size == 1) " person in." else " people in."),
                style = MaterialTheme.typography.bodyMedium
            )
            admitted.forEach { spent ->
                Text(
                    InviteCode.format(spent.code) + ", used " + dayOf(spent.usedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** The roles a code may grant, in the order the chips show them. OWNER is never grantable. */
private val GRANTABLE = listOf(MemberRole.CONTRIBUTOR, MemberRole.KEEPER, MemberRole.VIEWER)

private fun roleName(role: MemberRole): String = when (role) {
    MemberRole.OWNER -> "Owner"
    MemberRole.KEEPER -> "Keeper"
    MemberRole.CONTRIBUTOR -> "Contributor"
    MemberRole.VIEWER -> "Viewer"
}

/** What each role may do, in the words the permission rules enforce (docs/SPEC.md, section 4). */
private fun roleSentence(role: MemberRole): String = when (role) {
    MemberRole.KEEPER ->
        "They join as a keeper, who can add and edit the family's records, and invite others."
    MemberRole.CONTRIBUTOR ->
        "They join as a contributor, who can add recordings and people."
    MemberRole.VIEWER ->
        "They join as a viewer, who can read what the family shares and add nothing."
    MemberRole.OWNER -> "They join as the owner."
}

private fun dayOf(millis: Long?): String =
    if (millis == null) "at an unrecorded time"
    else SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(millis))
