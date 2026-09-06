package com.arv.app.feature.timeline

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.arv.app.core.ai.MemoryAccess
import com.arv.app.core.di.ServiceLocator
import com.arv.app.core.model.ArchiveArea
import com.arv.app.core.model.Story
import com.arv.app.ui.label
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class TimelineUiState(
    val decades: Map<Int?, List<Story>> = emptyMap(),
    /** Null is every archive. */
    val area: ArchiveArea? = null,
    /** Whether the family has anything readable at all, before the archive filter. */
    val anyAtAll: Boolean = false
)

class TimelineViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ServiceLocator.storyRepository(app)
    private val viewer = ServiceLocator.viewer
    private val area = MutableStateFlow<ArchiveArea?>(null)

    fun chooseArea(choice: ArchiveArea?) { area.value = choice }

    val state: StateFlow<TimelineUiState> =
        combine(
            repo.observeByDecade(ServiceLocator.familyId),
            repo.observePeople(ServiceLocator.familyId),
            area
        ) { byDecade, people, chosen ->
            // The same filter the feed and the librarian apply. A timeline is not a
            // separate permission surface, it is the same archive drawn on an axis,
            // and the DAO query behind it is deliberately unfiltered.
            //
            // Decades left empty by the filter are dropped rather than shown bare.
            // An empty year heading tells you something was withheld, and findGaps
            // would read the decade as present and hide a gap that is really there.
            val readable = byDecade
                .mapValues { (_, stories) ->
                    stories.filter { MemoryAccess.canRead(it, viewer, people) }
                }
                .filterValues { it.isNotEmpty() }
            // The archive filter runs after permission, never instead of it.
            val shown = if (chosen == null) {
                readable
            } else {
                readable
                    .mapValues { (_, stories) -> stories.filter { it.area == chosen } }
                    .filterValues { it.isNotEmpty() }
            }
            TimelineUiState(decades = shown, area = chosen, anyAtAll = readable.isNotEmpty())
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TimelineUiState())
}

/**
 * Screen 09.
 *
 * The gap cards are the point. A timeline that only shows what you have is a scrapbook;
 * one that shows what is missing, while the person who remembers it is still alive, is a
 * preservation tool.
 */
@Composable
fun TimelineScreen(
    onOpenStory: (String) -> Unit,
    onRecord: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: TimelineViewModel = viewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val decades = state.decades
    val presentDecades = decades.keys.filterNotNull().sorted()
    // Gaps are named across the whole archive. Inside one archive a missing decade is
    // not a hole somebody lived through, it is just a decade with no recipe in it.
    val gaps = if (state.area == null) findGaps(presentDecades) else emptyMap()

    if (!state.anyAtAll) {
        // Every other tab root has an empty state; this one rendered a blank white screen
        // to a family that just onboarded. The timeline is also the most natural place to
        // start, because it is the screen that asks what is missing.
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("No years yet", style = MaterialTheme.typography.headlineSmall)
            Text(
                "As stories come in they line up here by decade, and the years with " +
                    "nothing in them get named so someone can still be asked.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(onClick = onRecord) { Text("Record the first story") }
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "archives") {
            ArchiveRow(chosen = state.area, onChoose = viewModel::chooseArea)
        }
        if (decades.isEmpty()) {
            item(key = "empty-archive") {
                Text(
                    "Nothing in ${state.area?.label() ?: "the archive"} yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        decades.forEach { (decade, stories) ->
            item(key = "head-${decade ?: "unknown"}") {
                Text(
                    decade?.let { "${it}s" } ?: "Year unknown",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
            for (story in stories) {
                item(key = story.storyId) {
                    Card(
                        onClick = { onOpenStory(story.storyId) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(story.title, style = MaterialTheme.typography.titleMedium)
                            Text(
                                story.eraLabel + "  \u00b7  " + story.area.label(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            // One card per missing decade, not one card for the whole hole.
            if (decade != null) {
                gaps[decade]?.forEach { missing ->
                    item(key = "gap-$missing") { GapCard(missing, onRecord = onRecord) }
                }
            }
        }
    }
}

/** Every archive, then the four. One row, the same names as the pickers use. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ArchiveRow(chosen: ArchiveArea?, onChoose: (ArchiveArea?) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = chosen == null,
            onClick = { onChoose(null) },
            label = { Text("Everything") }
        )
        ArchiveArea.values().forEach { area ->
            FilterChip(
                selected = chosen == area,
                onClick = { onChoose(area) },
                label = { Text(area.label()) }
            )
        }
    }
}

@Composable
private fun GapCard(missingDecade: Int, onRecord: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("${missingDecade}s", style = MaterialTheme.typography.titleMedium)
            Text(
                "Nothing here yet. Someone in this family lived through it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // TODO(AI-8): open the prompt library filtered to this decade. Until the
            // library exists, the honest action is the recorder itself.
            OutlinedButton(onClick = onRecord) { Text("Ask about it") }
        }
    }
}

/**
 * For each decade that is followed by a hole, every decade actually missing after it.
 *
 * The old version returned only the decade before each hole, and the caller rendered a
 * single card for `decade + 10`. A 1950 to 1990 hole therefore advertised the 1960s as the
 * only thing missing and stayed silent about the 1970s and 1980s. On a screen whose entire
 * purpose is naming what is not recorded yet, hiding two thirds of a gap is the one bug it
 * cannot have.
 *
 * Internal rather than private so it can be tested without a device.
 */
internal fun findGaps(present: List<Int>): Map<Int, List<Int>> {
    if (present.size < 2) return emptyMap()
    return present.zipWithNext()
        .filter { (a, b) -> b - a > 10 }
        .associate { (a, b) -> a to ((a + 10) until b step 10).toList() }
}
