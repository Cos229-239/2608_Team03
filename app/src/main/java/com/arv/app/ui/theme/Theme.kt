package com.arv.app.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
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
    val target = kalos ?: if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = target.eased(),
        typography = ArvTypography,
        content = content
    )
}

/**
 * The same scheme with every major role easing toward its target, so choosing a theme
 * plays as one slow crossfade instead of a hard cut. 600ms to match the design system's
 * own transition. Colors only: nothing moves, which keeps it calm on an archive.
 */
@Composable
private fun androidx.compose.material3.ColorScheme.eased(): androidx.compose.material3.ColorScheme {
    @Composable
    fun c(target: Color): Color {
        val v by animateColorAsState(target, tween(600), label = "theme")
        return v
    }
    return copy(
        primary = c(primary), onPrimary = c(onPrimary),
        primaryContainer = c(primaryContainer), onPrimaryContainer = c(onPrimaryContainer),
        secondary = c(secondary), onSecondary = c(onSecondary),
        secondaryContainer = c(secondaryContainer), onSecondaryContainer = c(onSecondaryContainer),
        tertiary = c(tertiary), onTertiary = c(onTertiary),
        tertiaryContainer = c(tertiaryContainer), onTertiaryContainer = c(onTertiaryContainer),
        background = c(background), onBackground = c(onBackground),
        surface = c(surface), onSurface = c(onSurface),
        surfaceVariant = c(surfaceVariant), onSurfaceVariant = c(onSurfaceVariant),
        surfaceContainerLowest = c(surfaceContainerLowest),
        surfaceContainerLow = c(surfaceContainerLow),
        surfaceContainer = c(surfaceContainer),
        surfaceContainerHigh = c(surfaceContainerHigh),
        surfaceContainerHighest = c(surfaceContainerHighest),
        outline = c(outline), error = c(error), onError = c(onError)
    )
}

/**
 * The page's ground: theme background with the Kalos glow pooled into it.
 *
 * The design system never paints a flat dark field. The accents pool at low alpha, one
 * high, one low, one center, which is what gives the dark themes depth instead of
 * flatness. Drawn once here behind every screen; content sits on transparent surfaces so
 * the glow reads through. Heirloom paints its plain paper and skips the theatrics.
 */
@Composable
fun ArvBackground(content: @Composable () -> Unit) {
    val halos = ThemeController.current.halos()
    val bg = MaterialTheme.colorScheme.background
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(bg)
            if (halos.isNotEmpty()) {
                val (h1, h2, h3) = halos
                val r = size.maxDimension
                drawRect(
                    Brush.radialGradient(
                        listOf(h1, Color.Transparent),
                        center = Offset(size.width * 0.85f, size.height * 0.10f),
                        radius = r * 0.55f
                    )
                )
                drawRect(
                    Brush.radialGradient(
                        listOf(h2, Color.Transparent),
                        center = Offset(size.width * 0.10f, size.height * 0.85f),
                        radius = r * 0.50f
                    )
                )
                drawRect(
                    Brush.radialGradient(
                        listOf(h3, Color.Transparent),
                        center = Offset(size.width * 0.50f, size.height * 0.45f),
                        radius = r * 0.60f
                    )
                )
            }
        }
        content()
    }
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
