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

    // Accent (single value, not theme-dependent on iOS)
    val accent = Color(0xFF0062FF)
    val accentDim = Color(0xFF1E68DF)

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
