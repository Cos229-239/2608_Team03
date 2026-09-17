package com.arv.app.feature.settings

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.arv.app.core.di.ServiceLocator
import com.arv.app.core.sync.SyncScheduler
import com.arv.app.core.sync.SyncSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SyncViewModel(app: Application) : AndroidViewModel(app) {

    /**
     * The archive this screen is about, or null when it cannot be shared at all: the sample
     * family, or an archive with no account behind it. Read once, like every other screen's
     * family, because leaving an archive leaves this screen too.
     */
    val familyId: String? =
        SyncSettings.eligibleFamily()?.takeIf { ServiceLocator.syncEngine(app).available }

    data class Ui(
        val on: Boolean = false,
        val running: Boolean = false,
        val waiting: Int = 0,
        val last: SyncSettings.Last? = null
    )

    val ui: StateFlow<Ui> = familyId?.let { family ->
        combine(
            SyncSettings.version,
            SyncScheduler.observeRunning(app),
            SyncScheduler.observeWaiting(app, family)
        ) { _, running, waiting ->
            Ui(
                on = SyncSettings.isOn(app, family),
                running = running,
                waiting = waiting,
                last = SyncSettings.last(app, family)
            )
        }
    }?.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Ui())
        ?: flowOf(Ui()).stateIn(viewModelScope, SharingStarted.Eagerly, Ui())

    fun setOn(on: Boolean) {
        val family = familyId ?: return
        val app = getApplication<Application>()
        SyncSettings.setOn(app, family, on)
        if (on) syncNow() else SyncScheduler.stop(app)
    }

    /**
     * Asked for by a person, so anything the server turned down earlier gets another try:
     * whatever made it refuse may have changed where this phone cannot see it.
     */
    fun syncNow() {
        val family = familyId ?: return
        val app = getApplication<Application>()
        viewModelScope.launch {
            SyncScheduler.forgetRefusals(app, family)
            SyncScheduler.syncNow(app)
        }
    }
}

/**
 * The switch that shares this archive with the family's other phones, and a plain account of
 * what that sends, what it never sends, and how the last attempt went.
 *
 * Off until somebody turns it on. Shown only for an archive that can be shared.
 */
@Composable
fun SyncSection(
    modifier: Modifier = Modifier,
    viewModel: SyncViewModel = viewModel()
) {
    if (viewModel.familyId == null) {
        Text(
            "This archive has no account behind it, so it stays on this phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier
        )
        return
    }

    val ui by viewModel.ui.collectAsStateWithLifecycle()

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Share this archive with the family's phones",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            Switch(checked = ui.on, onCheckedChange = viewModel::setOn)
        }

        Text(
            if (ui.on) {
                "Stories set to Family, Branch or Selected, the family tree, and who is in the " +
                    "family go to everyone's phones through the family's server. Private " +
                    "stories and health records never leave this phone. Recordings and photos " +
                    "are not shared yet."
            } else {
                "Off. Everything stays on this phone. Turning it off later stops this phone " +
                    "sharing; what the family already has stays with them."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (ui.on) {
            val (line, isProblem) = statusLine(ui)
            Text(
                line,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isProblem) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface
            )
            OutlinedButton(
                onClick = viewModel::syncNow,
                enabled = !ui.running,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
            ) { Text(if (ui.running) "Sharing now" else "Sync now") }
        }
    }
}

/** What to say about the last attempt, and whether it is something the person should fix. */
private fun statusLine(ui: SyncViewModel.Ui): Pair<String, Boolean> {
    val waiting = when (ui.waiting) {
        0 -> ""
        1 -> " 1 change is waiting to go."
        else -> " ${ui.waiting} changes are waiting to go."
    }
    val last = ui.last
    return when {
        ui.running -> "Sharing now." to false
        last == null -> ("Not shared yet." + waiting) to false
        else -> when (last.outcome) {
            SyncSettings.Outcome.UP_TO_DATE -> {
                val refused = when (last.refused) {
                    0 -> ""
                    1 -> " The family's rules did not accept 1 change from this account."
                    else -> " The family's rules did not accept ${last.refused} changes from this account."
                }
                ("Shared at ${timeOf(last.at)}." + refused + waiting) to (last.refused > 0)
            }
            SyncSettings.Outcome.OFFLINE ->
                ("No connection at ${timeOf(last.at)}. It tries again on its own." + waiting) to false
            SyncSettings.Outcome.NOT_A_MEMBER ->
                ("The family's server does not count this account as part of this family. " +
                    "Nothing on this phone was deleted.") to true
            SyncSettings.Outcome.SIGNED_OUT ->
                "Sign in again to share this archive." to true
            SyncSettings.Outcome.PULL_REFUSED ->
                ("Sent what this phone had at ${timeOf(last.at)}, but could not bring down " +
                    "the family's changes." + waiting) to true
        }
    }
}

private fun timeOf(millis: Long): String =
    SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(millis))
