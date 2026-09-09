package com.arv.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.arv.app.core.data.Portrait
import java.io.File

/**
 * One person's face, or their initials when there is no face to draw.
 *
 * The circle was written inline on the feed, the people list and the profile header, so
 * putting a photograph in it three times would have meant three chances to forget the
 * permission check. It lives here once instead.
 *
 * [localPath] is expected to have come from
 * [com.arv.app.core.data.StoryRepository.observePortraits], which has already run the
 * photograph past [Portrait]. This composable does no permission work of its own, and it
 * must not: a screen that hands it a path straight out of an asset row would be drawing
 * something nobody checked. Null means initials, and initials are the design rather than a
 * placeholder, so there is nothing to apologise for when a portrait is withheld.
 */
@Composable
fun PersonAvatar(
    displayName: String,
    localPath: String?,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    ringColor: Color = MaterialTheme.colorScheme.primary,
    background: Color = MaterialTheme.colorScheme.surfaceVariant,
    initialsColor: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .border(2.dp, ringColor, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        // Checked here as well as in the repository because the file can go between the
        // query and the frame, and a broken image in a circle reads as a bug in the app
        // rather than as a missing file.
        val drawable = localPath?.takeIf { File(it).exists() }

        if (drawable != null) {
            AsyncImage(
                model = File(drawable),
                contentDescription = displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size).clip(CircleShape)
            )
        } else {
            Text(
                Portrait.initialsOf(displayName),
                // Scaled off the circle so one component serves the 64dp strip on the feed
                // and the small rows on the people list without a second set of numbers.
                fontSize = (size.value * 0.34f).sp,
                style = MaterialTheme.typography.titleLarge,
                color = initialsColor
            )
        }
    }
}
