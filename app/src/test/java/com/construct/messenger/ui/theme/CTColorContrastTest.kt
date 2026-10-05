package com.construct.messenger.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * The accent is text in this app — `CTSettingsRow` action rows, `ConstructActionRow`, the
 * onboarding copy, the `>` system-message prefix — so it owes 4.5:1 on the surfaces it lands on.
 *
 * The value replaced here, 0x0062FF, did not: 3.97 on [CTColor.bg]. Nothing said so, because a
 * palette is a list of hexes and a comment claiming it matches iOS. This measures it instead.
 * The numbers are WCAG 2.1 relative luminance and match the ones in iOS
 * `ConstructTheme.swift` to two decimals — that is the parity check, not a second copy of the
 * hexes.
 */
class CTColorContrastTest {

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

    private fun assertReadable(fg: Color, bg: Color, name: String) {
        val ratio = contrast(fg, bg)
        assertTrue(
            "$name reads %.2f — accent is text in this app and owes 4.5:1".format(ratio),
            ratio >= 4.5
        )
    }

    /** Mutation: put 0x0062FF back as [CTColor.accent] — this reddens at 3.97. */
    @Test
    fun accentIsReadableOnEverySurfaceItIsTextOn() {
        assertReadable(CTColor.accent, CTColor.bg, "accent on bg")
        assertReadable(CTColor.accent, CTColor.bgMsg, "accent on a bgMsg card")
        assertReadable(CTColor.accentLight, CTColor.bgLight, "accentLight on bgLight")
        assertReadable(CTColor.accentLight, CTColor.bgMsgLight, "accentLight on a light card")
    }

    /** `CTButton` draws its label in [CTColor.bg] on an [CTColor.accent] fill. */
    @Test
    fun aButtonLabelIsReadableOnItsOwnFill() {
        assertReadable(CTColor.bg, CTColor.accent, "bg label on an accent fill")
    }

    /**
     * The evidence for "fill, hover and status dots only — never text" on [CTColor.accentDim].
     * It is an inverted assertion on purpose: a dim partner that *did* clear the bar would mean
     * the rule can be dropped, and this is where someone would find that out.
     *
     * Mutation: make `accentDim` equal `accent` — this reddens, and the fix is to delete the rule
     * in `Color.kt`, not the test.
     */
    @Test
    fun theDimAccentCannotBeText() {
        assertTrue(
            "accentDim now clears 4.5:1 — revisit the never-text rule in Color.kt",
            contrast(CTColor.accentDim, CTColor.bg) < 4.5
        )
    }

    /**
     * [CTColor.online] is a dot, not text: it owes 3:1 (WCAG 1.4.11, non-text) on the background
     * it sits on. Mutation: make `onlineLight` the platform 0x34C759 — this reddens at about 2.0.
     */
    @Test
    fun theOnlineDotIsVisibleInBothThemes() {
        for ((fg, bg, name) in listOf(
            Triple(CTColor.onlineDark, CTColor.bgDark, "online on bg"),
            Triple(CTColor.onlineLight, CTColor.bgLight, "onlineLight on bgLight"),
        )) {
            val ratio = contrast(fg, bg)
            assertTrue("$name reads %.2f — a status dot owes 3:1".format(ratio), ratio >= 3.0)
        }
    }

    /** Mutation: put 0x818181 back as [CTColor.textDimDark] — this reddens at 4.18 on a card. */
    @Test
    fun secondaryTextIsReadableOnCards() {
        assertReadable(CTColor.textDimDark, CTColor.bgDark, "textDim on bg")
        assertReadable(CTColor.textDimDark, CTColor.bgMsgDark, "textDim on a bgMsg card")
        assertReadable(CTColor.textDimLight, CTColor.bgLight, "textDimLight on bgLight")
        assertReadable(CTColor.textDimLight, CTColor.bgMsgLight, "textDimLight on a light card")
    }
}
