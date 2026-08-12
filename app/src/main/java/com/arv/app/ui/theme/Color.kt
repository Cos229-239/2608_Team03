package com.arv.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Palette taken from the hi-fi mockups: warm paper, deep forest, a single terracotta
 * accent, and brass for anything the family verified.
 *
 * Every foreground/background pair below is chosen to clear WCAG AA (4.5:1 for body text,
 * 3:1 for large text and controls) in both schemes. The muted tones are deliberately
 * darker than they look in the mockups, because the secondary user of this app is in their
 * eighties and the light-gray metadata in the comps would not have passed. Contrast here
 * is a functional requirement, not a taste call. See docs/SPEC.md §8.
 */

// --- Light ---
/** Warm paper. Not white; white reads clinical and this is a arv. */
val PaperLight = Color(0xFFFAF6EC)
val PaperRaisedLight = Color(0xFFF1EADA)

/** Near-black with green in it, so body text sits in the same family as the brand. */
val InkLight = Color(0xFF1B2A20)

/** Metadata and secondary lines. Darkened from the comps to clear 4.5:1 on paper. */
val InkMutedLight = Color(0xFF4F5A4F)

/** Primary actions: Begin, Join family, Record. */
val ForestLight = Color(0xFF1E4029)
val ForestContainerLight = Color(0xFFDDE8DC)

/** The one accent. Used sparingly, for the single most important action on a screen. */
val TerracottaLight = Color(0xFF9E4726)
val TerracottaContainerLight = Color(0xFFF6DFD2)

/** Reserved for provenance: things a human recorded, wrote, or verified. */
val BrassLight = Color(0xFF7A5C1E)

val LineLight = Color(0xFFD9D1BE)
val AlertLight = Color(0xFF8C2F1D)

// --- Dark ---
val PaperDark = Color(0xFF15180F)
val PaperRaisedDark = Color(0xFF1F241B)
val InkDark = Color(0xFFF1EADA)
val InkMutedDark = Color(0xFFB3BBAC)
val ForestDark = Color(0xFF9CC7A6)
val ForestContainerDark = Color(0xFF27402D)
val TerracottaDark = Color(0xFFE9A183)
val TerracottaContainerDark = Color(0xFF4A2416)
val BrassDark = Color(0xFFD8B571)
val LineDark = Color(0xFF353B30)
val AlertDark = Color(0xFFF0A697)
