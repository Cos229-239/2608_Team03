package com.arv.app.feature.timeline

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
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
import com.arv.app.core.model.Story
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class TimelineViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ServiceLocator.storyRepository(app)
    private val viewer = ServiceLocator.viewer

    val decades: StateFlow<Map<Int?, List<Story>>> =
        repo.observeByDecade(ServiceLocator.familyId)
            .map { byDecade ->
                // The same filter the feed and the librarian apply. A timeline is not a
                // separate permission surface, it is the same archive drawn on an axis,
                // and the DAO query behind it is deliberately unfiltered.
                //
                // Decades left empty by the filter are dropped rather than shown bare.
                // An empty year heading tells you something was withheld, and findGaps
                // would read the decade as present and hide a gap that is really there.
                byDecade
                    .mapValues { (_, stories) ->
                        stories.filter { MemoryAccess.canRead(it, viewer) }
                    }
                    .filterValues { it.isNotEmpty() }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
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
    val decades by viewModel.decades.collectAsStateWithLifecycle()
    val presentDecades = decades.keys.filterNotNull().sorted()
    val gaps = findGaps(presentDecades)

    if (decades.isEmpty()) {
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
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
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
                                story.eraLabel,
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
