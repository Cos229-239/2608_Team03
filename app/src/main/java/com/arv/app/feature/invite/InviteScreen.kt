package com.arv.app.feature.invite

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Inviting somebody, as its own page, reached from the home screen.
 *
 * Inviting worked from Settings for a week while the home screen said nothing about it, and
 * the weekly tester report of 13 September recorded it as not built. A feature nobody can
 * find is a feature the family does not have. Settings keeps its copy for whoever looks
 * there first.
 */
@Composable
fun InviteScreen(modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 48.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { Text("Invite someone", style = MaterialTheme.typography.headlineMedium) }
        item { InviteSection() }
    }
}
