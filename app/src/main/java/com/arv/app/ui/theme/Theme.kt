package com.arv.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

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
    // A chosen Kalos theme overrides the system light/dark split: they are dark by
    // design, and the choice is the point. Heirloom keeps following the system.
    val kalos = ThemeController.current.scheme()
    MaterialTheme(
        colorScheme = kalos ?: if (darkTheme) DarkColors else LightColors,
        typography = ArvTypography,
        content = content
    )
}

/**
 * The hero-panel palette: the dark headed blocks on Home, People and Documents.
 *
 * Heirloom keeps its hand-tuned forest-and-paper hero exactly as designed. A Kalos theme
 * renders the same structure as a raised dark panel with the accent doing the pointing,
 * which is how that design system uses color. Screens name the role and the theme decides
 * the value, so switching themes restyles the heroes instead of leaving green islands.
 */
object ArvHero {
    private val kalos: Boolean
        @Composable get() = ThemeController.current != ThemeOption.HEIRLOOM

    /** The panel itself. */
    val container: Color
        @Composable get() =
            if (kalos) MaterialTheme.colorScheme.surfaceContainerHigh else ForestLight

    /** The lighter end of the panel's gradient. */
    val containerBright: Color
        @Composable get() =
            if (kalos) MaterialTheme.colorScheme.surfaceContainer else Color(0xFF3A5741)

    /** Text and icons standing on the panel. */
    val on: Color
        @Composable get() = if (kalos) MaterialTheme.colorScheme.onSurface else PaperLight

    /** Dark foreground for the light chips that sit on the panel. */
    val ink: Color
        @Composable get() = if (kalos) MaterialTheme.colorScheme.background else InkLight

    /** Provenance and small accents on the panel. Brass in Heirloom. */
    val accent: Color
        @Composable get() = if (kalos) MaterialTheme.colorScheme.tertiary else BrassDark

    /** The one action that matters most. Terracotta in Heirloom. */
    val cta: Color
        @Composable get() = if (kalos) MaterialTheme.colorScheme.primary else TerracottaLight
}
