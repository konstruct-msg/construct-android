package com.construct.messenger.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Design-system color tokens.
 *
 * **Canon:** iOS `ConstructMessenger/Utilities/ConstructTheme.swift` → `Color.CT`.
 * Values must match the iOS app exactly — do not eyeball them. iOS defines most
 * tokens as `dark/light` pairs; we mirror that with `<token>` (dark) + `<token>Light`.
 */
object CTColor {
    // Backgrounds
    val bg = Color(0xFF090909)          // dark 0x090909 / light 0xF2F2F2
    val bgLight = Color(0xFFF2F2F2)
    val bgMsg = Color(0xFF202020)       // incoming bubble — dark 0x202020 / light 0xE2E2E2
    val bgMsgLight = Color(0xFFE2E2E2)
    val outMsgBg = Color(0xFF111111)    // outgoing bubble — dark 0x111111 / light 0xE9E9E9
    val outMsgBgLight = Color(0xFFE9E9E9)

    // Accent
    /**
     * Primary accent — dark 0x008CFF / light 0x0057E0.
     *
     * Adaptive, and it has to be: a single hex cannot clear 4.5:1 as text in both themes, and
     * accent *is* text here — `CTSettingsRow` action rows, `ConstructActionRow`, the onboarding
     * copy. The value this replaced, 0x0062FF, read 3.97 on [bg]: below the bar everywhere it was
     * used as text, and below it under `CTButton`'s own label, which draws in [bg] on this fill.
     *
     * 0x008CFF is 5.87 on [bg] and 4.81 on a [bgMsg] card; 0x0057E0 is 5.45 / 4.71 on the light
     * pair. `CTColorContrastTest` measures all four — do not eyeball a replacement.
     */
    val accent = Color(0xFF008CFF)
    val accentLight = Color(0xFF0057E0)

    /**
     * Pressed / hover partner of [accent] — dark 0x0077DB / light 0x0047B3.
     *
     * **Fill, hover and status dots only — never text.** No dim partner of this accent reaches
     * 4.5:1 on [bg] (0x0077DB peaks at 4.42), so text that used to be `accentDim` is [accent]
     * instead. `CTColorContrastTest` asserts the 4.42, so the rule has its evidence beside it
     * rather than only in this comment.
     */
    val accentDim = Color(0xFF0077DB)
    val accentDimLight = Color(0xFF0047B3)

    // Text
    val text = Color(0xFFE8E8E8)        // dark 0xE8E8E8 / light 0x111111
    val textLight = Color(0xFF111111)
    val textDim = Color(0xFF818181)     // dark 0x818181 / light 0x333333
    val textDimLight = Color(0xFF333333)
    val outMsgText = Color(0xFFFFFFFF)  // dark #FFFFFF / light #111111
    val outMsgTextLight = Color(0xFF111111)

    // Structure (separators, ASCII noise)
    val noise = Color(0xFF1E1E1E)       // dark 0x1E1E1E / light 0xC8C8C8
    val noiseLight = Color(0xFFC8C8C8)

    // Disabled button background (ConstructTheme.swift CTButton)
    val disabledBg = Color(0xFF1C1C1C)  // dark 0x1C1C1C / light 0xD8D8D8
    val disabledBgLight = Color(0xFFD8D8D8)

    // Danger (single value)
    val danger = Color(0xFFDC3C3C)
}
