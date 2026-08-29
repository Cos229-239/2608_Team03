package com.arv.app.ui.theme

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * The Kalos theme family.
 *
 * Each palette is four background depths, one ink at three strengths, an accent pair, and
 * the warn/privacy colors, mapped onto Material roles the same way in every theme so the
 * meaning of a color survives switching: primary is the accent, tertiary is the brighter
 * accent reserved for the one action that matters most, secondary keeps the provenance
 * role brass holds in Heirloom.
 *
 * All Kalos themes are dark by design. Heirloom stays the default and keeps its own
 * light/dark pair: the archive's identity is warm paper and forest, and these are
 * options, not a replacement.
 */
enum class ThemeOption(
    val label: String,
    private val palette: Kalos?
) {
    HEIRLOOM("Heirloom", null),
    MERIDIAN("Meridian", Kalos(0xFF04040A, 0xFF0A0A14, 0xFF10101E, 0xFF16162A,
        0xFFFFFFFF, 0xFF4570FF, 0xFF8080F7, 0xFF6464B5, 0xFFFFB87A, 0xFFFF9988)),
    CYBER("Cyber", Kalos(0xFF04060B, 0xFF070A12, 0xFF0C1120, 0xFF131A2A,
        0xFFF0F4FF, 0xFF7DD3FC, 0xFFB8E8FF, 0xFFB49AFF, 0xFFFFC97A, 0xFFFF82B4)),
    MONO("Mono", Kalos(0xFF08080A, 0xFF0E0E12, 0xFF16161B, 0xFF1F1F26,
        0xFFFFFFFF, 0xFFFAFAFA, 0xFFD4D4D8, 0xFFA1A1AA, 0xFFFFB87A, 0xFFFF9988)),
    SAGE("Sage", Kalos(0xFF060A08, 0xFF0A1210, 0xFF121C18, 0xFF1A2820,
        0xFFF4FFF8, 0xFF84CC95, 0xFFBBE8C0, 0xFFD9E8B7, 0xFFF4D49C, 0xFFE8B589)),
    CRIMSON("Crimson", Kalos(0xFF0A0404, 0xFF14080A, 0xFF1F0E12, 0xFF2E141A,
        0xFFFFF5F5, 0xFFDC2626, 0xFFF87171, 0xFFD97706, 0xFFF59E0B, 0xFFFB7185)),
    GALAXY("Galaxy", Kalos(0xFF08041A, 0xFF0E0826, 0xFF160E36, 0xFF1E144A,
        0xFFF8F4FF, 0xFFD946EF, 0xFFF0ABFC, 0xFF22D3EE, 0xFFFBBF24, 0xFFFF82B4)),
    AURORA("Aurora", Kalos(0xFF0A0712, 0xFF110A1B, 0xFF1A1028, 0xFF25173A,
        0xFFFBF6F7, 0xFFFFB87A, 0xFFFFD9A0, 0xFFFF9988, 0xFFFFD66B, 0xFFFF9988));

    /** Null means Heirloom: use the hand-built light/dark schemes in Theme.kt. */
    fun scheme(dark: Boolean): ColorScheme? =
        palette?.let { if (dark) it.toScheme() else it.toLightScheme() }

    /** The accent pair at low strength, for the page's atmospheric glow. */
    fun halos(): List<Color> = palette?.halos() ?: emptyList()
}

/** One Kalos palette, in the order the design system declares them. */
class Kalos(
    private val bg: Long, private val bg1: Long, private val bg2: Long, private val bg3: Long,
    private val ink: Long, private val ac: Long, private val acB: Long, private val cy: Long,
    private val warn: Long, private val priv: Long
) {
    /**
     * The glow the design system pools behind a page: the accents at 6 to 8 percent,
     * which is enough to give the dark ground depth and never enough to cost contrast.
     */
    fun halos(): List<Color> = listOf(
        Color(ac).copy(alpha = 0.11f),
        Color(acB).copy(alpha = 0.08f),
        Color(cy).copy(alpha = 0.07f)
    )

    /**
     * The same theme in daylight.
     *
     * The design system ships only dark palettes, so the light ones are derived rather
     * than copied: the ground is white with the accent breathed into it, the ink is the
     * theme's own night sky, and any accent too pale to read on white is deepened until
     * it can. Every theme keeps its temperature either way, which is what makes one name
     * cover both.
     */
    fun toLightScheme(): ColorScheme {
        val inkC = Color(bg)
        fun tint(f: Float): Color {
            val a = Color(ac)
            return Color(
                1f + (a.red - 1f) * f,
                1f + (a.green - 1f) * f,
                1f + (a.blue - 1f) * f
            )
        }
        val bgL = tint(0.045f)
        return androidx.compose.material3.lightColorScheme(
            primary = Color(ac).deepEnough(),
            onPrimary = bgL,
            primaryContainer = tint(0.12f),
            onPrimaryContainer = inkC,

            secondary = Color(cy).deepEnough(),
            onSecondary = bgL,
            secondaryContainer = tint(0.07f),
            onSecondaryContainer = inkC,

            tertiary = Color(acB).deepEnough(),
            onTertiary = bgL,
            tertiaryContainer = tint(0.10f),
            onTertiaryContainer = inkC,

            background = bgL,
            onBackground = inkC,
            surface = tint(0.02f),
            onSurface = inkC,
            surfaceVariant = tint(0.07f),
            onSurfaceVariant = inkC.copy(alpha = 0.72f),
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = tint(0.03f),
            surfaceContainer = tint(0.05f),
            surfaceContainerHigh = tint(0.08f),
            surfaceContainerHighest = tint(0.10f),
            outline = inkC.copy(alpha = 0.25f),
            error = Color(priv).deepEnough(),
            onError = bgL
        )
    }

    fun toScheme(): ColorScheme {
        val bgC = Color(bg)
        val inkC = Color(ink)
        return darkColorScheme(
            primary = Color(ac),
            onPrimary = bgC,
            primaryContainer = Color(bg3),
            onPrimaryContainer = inkC,

            secondary = Color(cy),
            onSecondary = bgC,
            secondaryContainer = Color(bg2),
            onSecondaryContainer = inkC,

            tertiary = Color(acB),
            onTertiary = bgC,
            tertiaryContainer = Color(bg3),
            onTertiaryContainer = inkC,

            background = bgC,
            onBackground = inkC,
            surface = Color(bg1),
            onSurface = inkC,
            surfaceVariant = Color(bg2),
            // The 72% ink step from the CSS tokens, as alpha so it sits on any depth.
            onSurfaceVariant = inkC.copy(alpha = 0.72f),
            surfaceContainerLowest = bgC,
            surfaceContainerLow = Color(bg1),
            surfaceContainer = Color(bg2),
            surfaceContainerHigh = Color(bg3),
            surfaceContainerHighest = Color(bg3),
            outline = inkC.copy(alpha = 0.22f),
            error = Color(priv),
            onError = bgC
        )
    }
}

/**
 * Which theme is open, restored before the first frame and persisted on change.
 *
 * Compose state so a choice made in Settings restyles every screen immediately; its own
 * prefs file so the session store stays about identity, not appearance.
 */
/** Follow the phone, or hold the page to one side of day. */
enum class ThemeMode { AUTO, LIGHT, DARK }

object ThemeController {

    private const val PREFS = "arv.theme"
    private const val KEY = "option"
    private const val KEY_MODE = "mode"

    var current by mutableStateOf(ThemeOption.HEIRLOOM)
        private set

    var mode by mutableStateOf(ThemeMode.AUTO)
        private set

    fun restore(context: Context) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.getString(KEY, null)?.let { name ->
            current = ThemeOption.entries.firstOrNull { it.name == name } ?: ThemeOption.HEIRLOOM
        }
        p.getString(KEY_MODE, null)?.let { name ->
            mode = ThemeMode.entries.firstOrNull { it.name == name } ?: ThemeMode.AUTO
        }
    }

    fun choose(option: ThemeOption, context: Context) {
        current = option
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, option.name).apply()
    }

    fun chooseMode(choice: ThemeMode, context: Context) {
        mode = choice
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_MODE, choice.name).apply()
    }
}

/**
 * An accent that reads on white. Mono's accent is nearly white itself, and half the
 * palettes sit too bright for light ground, so anything above the threshold is deepened
 * and everything already dark passes through untouched.
 */
private fun Color.deepEnough(): Color {
    val luminance = 0.299f * red + 0.587f * green + 0.114f * blue
    if (luminance <= 0.55f) return this
    return Color(red * 0.52f, green * 0.52f, blue * 0.56f, 1f)
}
