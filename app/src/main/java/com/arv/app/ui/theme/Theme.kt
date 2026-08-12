package com.arv.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * Role assignments, so the accent stays meaningful:
 *
 * - primary   forest, for the ordinary primary action on a screen
 * - tertiary  terracotta, for the ONE action that matters most on that screen
 * - secondary brass, reserved for provenance chips and human-verified markers
 *
 * If terracotta appears twice on the same screen, one of them is wrong.
 */
private val LightColors = lightColorScheme(
    primary = ForestLight,
    onPrimary = PaperLight,
    primaryContainer = ForestContainerLight,
    onPrimaryContainer = InkLight,

    secondary = BrassLight,
    onSecondary = PaperLight,
    // Tab pills and chip selections. Forest, not Material's default lavender.
    secondaryContainer = ForestContainerLight,
    onSecondaryContainer = InkLight,

    tertiary = TerracottaLight,
    onTertiary = PaperLight,
    tertiaryContainer = TerracottaContainerLight,
    onTertiaryContainer = InkLight,

    background = PaperLight,
    onBackground = InkLight,
    surface = PaperLight,
    onSurface = InkLight,
    surfaceVariant = PaperRaisedLight,
    onSurfaceVariant = InkMutedLight,
    // Cards and sheets pull from these. Left unset they fall back to Material's
    // neutral lavender, which is exactly the wrong temperature for this app.
    surfaceContainerLowest = PaperLight,
    surfaceContainerLow = PaperRaisedLight,
    surfaceContainer = PaperRaisedLight,
    surfaceContainerHigh = PaperRaisedLight,
    surfaceContainerHighest = PaperRaisedLight,
    outline = LineLight,
    error = AlertLight,
    onError = PaperLight
)

private val DarkColors = darkColorScheme(
    primary = ForestDark,
    onPrimary = PaperDark,
    primaryContainer = ForestContainerDark,
    onPrimaryContainer = InkDark,

    secondary = BrassDark,
    onSecondary = PaperDark,
    secondaryContainer = ForestContainerDark,
    onSecondaryContainer = InkDark,

    tertiary = TerracottaDark,
    onTertiary = PaperDark,
    tertiaryContainer = TerracottaContainerDark,
    onTertiaryContainer = InkDark,

    background = PaperDark,
    onBackground = InkDark,
    surface = PaperDark,
    onSurface = InkDark,
    surfaceVariant = PaperRaisedDark,
    onSurfaceVariant = InkMutedDark,
    surfaceContainerLowest = PaperDark,
    surfaceContainerLow = PaperRaisedDark,
    surfaceContainer = PaperRaisedDark,
    surfaceContainerHigh = PaperRaisedDark,
    surfaceContainerHighest = PaperRaisedDark,
    outline = LineDark,
    error = AlertDark,
    onError = PaperDark
)

/**
 * Deliberately not using dynamic color. The palette carries meaning here (brass marks what
 * a human verified), and a wallpaper-derived scheme would break that.
 */
@Composable
fun ArvTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = ArvTypography,
        content = content
    )
}
