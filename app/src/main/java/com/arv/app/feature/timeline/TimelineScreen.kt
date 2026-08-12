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
import com.arv.app.core.di.ServiceLocator
import com.arv.app.core.model.Story
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class TimelineViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ServiceLocator.storyRepository(app)

    val decades: StateFlow<Map<Int?, List<Story>>> =
        repo.observeByDecade(ServiceLocator.DEMO_FAMILY_ID)
            .map { it }
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
    modifier: Modifier = Modifier,
    viewModel: TimelineViewModel = viewModel()
) {
    val decades by viewModel.decades.collectAsStateWithLifecycle()
    val presentDecades = decades.keys.filterNotNull().sorted()
    val gaps = findGaps(presentDecades)

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
            if (decade != null && gaps.contains(decade)) {
                item(key = "gap-$decade") { GapCard(decade + 10) }
            }
        }
    }
}

@Composable
private fun GapCard(missingDecade: Int) {
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
            // TODO(AI-8): open the prompt library filtered to this decade.
            OutlinedButton(onClick = {}) { Text("Ask about it") }
        }
    }
}

/** Decades that are followed by a hole in the record. */
private fun findGaps(present: List<Int>): Set<Int> {
    if (present.size < 2) return emptySet()
    return present.zipWithNext()
        .filter { (a, b) -> b - a > 10 }
        .map { it.first }
        .toSet()
}
