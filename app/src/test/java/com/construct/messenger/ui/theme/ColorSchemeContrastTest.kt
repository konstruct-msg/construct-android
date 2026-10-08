package com.construct.messenger.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * Every Material role pair — text or icon colour on the surface it is drawn on — in both schemes
 * the app ships, held to WCAG 4.5:1.
 *
 * Step 0 of the Material 3 move (`docs/MATERIAL3_MIGRATION.md`). [CTColorContrastTest] measures
 * the CT palette; this measures what a Material component actually draws, which is a role, not a
 * CT token — a `Button` sets its label in `onPrimary` whatever `CTButton` did. Once screens are
 * Material, a pair that fails here is a control someone cannot read.
 *
 * Pairs below the bar today are listed in [knownBelow] with the ratio measured when they were
 * listed. The list may only shrink: a listed pair that now clears the bar fails too, so the entry
 * is removed in the same change that fixed it. A new pair below the bar is not added to the list
 * to make this pass — the scheme is fixed instead.
 */
class ColorSchemeContrastTest {

    private fun luminance(color: Color): Double {
        fun channel(c: Float): Double {
            val v = c.toDouble()
            return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(color.red) +
            0.7152 * channel(color.green) +
            0.0722 * channel(color.blue)
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        val (hi, lo) = if (la > lb) la to lb else lb to la
        return (hi + 0.05) / (lo + 0.05)
    }

    /** (name, foreground, background) for every role a Material component sets text or icons in. */
    private fun pairs(s: ColorScheme): List<Triple<String, Color, Color>> = listOf(
        Triple("onPrimary/primary", s.onPrimary, s.primary),
        Triple("onPrimaryContainer/primaryContainer", s.onPrimaryContainer, s.primaryContainer),
        Triple("onSecondary/secondary", s.onSecondary, s.secondary),
        Triple("onSecondaryContainer/secondaryContainer", s.onSecondaryContainer, s.secondaryContainer),
        Triple("onTertiary/tertiary", s.onTertiary, s.tertiary),
        Triple("onTertiaryContainer/tertiaryContainer", s.onTertiaryContainer, s.tertiaryContainer),
        Triple("onError/error", s.onError, s.error),
        Triple("onErrorContainer/errorContainer", s.onErrorContainer, s.errorContainer),
        Triple("onBackground/background", s.onBackground, s.background),
        Triple("onSurface/surface", s.onSurface, s.surface),
        Triple("onSurfaceVariant/surface", s.onSurfaceVariant, s.surface),
        Triple("onSurfaceVariant/surfaceVariant", s.onSurfaceVariant, s.surfaceVariant),
        Triple("inverseOnSurface/inverseSurface", s.inverseOnSurface, s.inverseSurface),
        Triple("onSurface/surfaceContainerLowest", s.onSurface, s.surfaceContainerLowest),
        Triple("onSurface/surfaceContainerLow", s.onSurface, s.surfaceContainerLow),
        Triple("onSurface/surfaceContainer", s.onSurface, s.surfaceContainer),
        Triple("onSurface/surfaceContainerHigh", s.onSurface, s.surfaceContainerHigh),
        Triple("onSurface/surfaceContainerHighest", s.onSurface, s.surfaceContainerHighest),
        Triple("onSurfaceVariant/surfaceContainer", s.onSurfaceVariant, s.surfaceContainer),
        Triple("onSurfaceVariant/surfaceContainerHigh", s.onSurfaceVariant, s.surfaceContainerHigh),
        Triple("primary/background", s.primary, s.background),
        Triple("primary/surface", s.primary, s.surface),
        Triple("error/surface", s.error, s.surface),
    )

    private val schemes = listOf("dark" to DarkColorScheme, "light" to LightColorScheme)

    /**
     * Below 4.5:1 after the theme bridge (migration step 1, 2026-10-08). All three are
     * `CTColor.danger` (0xDC3C3C), now the `error` role:
     * - white on it, 4.42 — the destructive `CTButton` label and the swipe actions. Before the
     *   bridge the same pair was listed as `tertiary`; it moved role, it did not appear;
     * - as text on the light ground, 3.95. CT has drawn it so since it had a light theme; the
     *   `error` role was the library's own red until the bridge, so nothing measured it.
     * The bridge fixed one: white on the dark accent (3.39), now the ground on it (5.87).
     * The fix for these is a danger value per theme, which is a design change, not a bridge one.
     */
    private val knownBelow = mapOf(
        "dark onError/error" to 4.42,
        "light onError/error" to 4.42,
        "light error/surface" to 3.95,
    )

    @Test
    fun everyRolePairReads() {
        val below = schemes.flatMap { (theme, s) ->
            pairs(s).map { (name, fg, bg) -> "$theme $name" to contrast(fg, bg) }
        }.filter { it.second < 4.5 }.toMap()

        val newlyBelow = below.keys - knownBelow.keys
        assertTrue(
            "below 4.5:1 and not known: " +
                newlyBelow.joinToString { "$it %.2f".format(below.getValue(it)) },
            newlyBelow.isEmpty(),
        )
        val fixed = knownBelow.keys - below.keys
        assertTrue("now clear 4.5:1 — remove from knownBelow: $fixed", fixed.isEmpty())
    }
}
