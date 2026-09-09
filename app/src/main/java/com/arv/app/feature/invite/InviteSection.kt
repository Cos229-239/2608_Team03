package com.arv.app.feature.invite

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
    private val familyId = ServiceLocator.familyId
    private val userId = ServiceLocator.userId

    private val _code = MutableStateFlow<InviteEntity?>(null)
    val code: StateFlow<InviteEntity?> = _code.asStateFlow()

    var working by mutableStateOf(false)
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
            _code.value = runCatching {
                repo.inviteCodeFor(
                    familyId = familyId,
                    userId = userId,
                    familyName = ActiveSession.familyName,
                    nowMillis = System.currentTimeMillis()
                )
            }.getOrNull()
            working = false
        }
    }

    fun replaceCode() {
        if (working) return
        working = true
        viewModelScope.launch {
            _code.value = runCatching {
                repo.replaceInviteCode(
                    familyId = familyId,
                    userId = userId,
                    familyName = ActiveSession.familyName,
                    nowMillis = System.currentTimeMillis()
                )
            }.getOrNull() ?: _code.value
            working = false
        }
    }
}

/**
 * The inviting half of the invitation, for whoever already has an archive open.
 *
 * Hidden from viewers on purpose. A viewer reads what the family shows everyone; deciding
 * who else gets to stand in the family is not a reading.
 */
@Composable
fun InviteSection(
    modifier: Modifier = Modifier,
    viewModel: InviteViewModel = viewModel()
) {
    if (ActiveSession.role == MemberRole.VIEWER) return

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
            "Read this out to one person. It works once, and the archive records that you " +
                "are the one who let them in.",
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
                Text(
                    "They join as a contributor, who can add recordings and people.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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

private fun dayOf(millis: Long?): String =
    if (millis == null) "at an unrecorded time"
    else SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(millis))
