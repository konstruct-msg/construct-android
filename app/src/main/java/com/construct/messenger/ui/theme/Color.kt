package com.construct.messenger.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * Design-system color tokens.
 *
 * **Canon:** iOS `ConstructMessenger/Utilities/ConstructTheme.swift` → `Color.CT`.
 * Values must match the iOS app exactly — do not eyeball them. iOS defines most
 * tokens as `dark/light` pairs; we mirror that with `<token>Dark` + `<token>Light`,
 * and `<token>` returns the half for the current theme ([isDark]).
 */
object CTColor {
    /**
     * Which half of each pair the unsuffixed tokens return.
     *
     * Snapshot state, so every composable (and every draw lambda) that read a token recomposes
     * when it flips; the tokens stay plain getters, which keeps them usable outside composition
     * (`CTStatus.color`, the system bar scrim). Set by `MainActivity` from Settings → Appearance.
     * The `…Dark` / `…Light` halves are fixed values for places that need one side explicitly.
     */
    var isDark: Boolean by mutableStateOf(true)

    /**
     * The Material roles of the current theme. A token below that has a role reads it, so a change
     * of scheme in `Theme.kt` repaints every site that reads the token — the ~900 of them did not
     * have to change for that (`docs/MATERIAL3_MIGRATION.md`, step 1). The `…Dark` / `…Light`
     * halves further down are the palette the schemes are built from.
     */
    private val roles: ColorScheme get() = if (isDark) DarkColorScheme else LightColorScheme

    val bg: Color get() = roles.background
    val bgMsg: Color get() = roles.surfaceContainerHighest
    val accent: Color get() = roles.primary
    val accentDim: Color get() = roles.primaryContainer
    val text: Color get() = roles.onSurface
    val textDim: Color get() = roles.onSurfaceVariant
    val noise: Color get() = roles.outlineVariant
    val danger: Color get() = roles.error
    val warning: Color get() = roles.tertiary

    // No Material role: a bubble, a status. They stay CT tokens.
    val outMsgBg: Color get() = if (isDark) outMsgBgDark else outMsgBgLight
    val outMsgText: Color get() = if (isDark) outMsgTextDark else outMsgTextLight
    val online: Color get() = if (isDark) onlineDark else onlineLight

    // Backgrounds
    val bgDark = Color(0xFF090909)          // dark 0x090909 / light 0xF2F2F2
    val bgLight = Color(0xFFF2F2F2)
    val bgMsgDark = Color(0xFF202020)       // incoming bubble — dark 0x202020 / light 0xE2E2E2
    val bgMsgLight = Color(0xFFE2E2E2)
    val outMsgBgDark = Color(0xFF111111)    // outgoing bubble — dark 0x111111 / light 0xE9E9E9
    val outMsgBgLight = Color(0xFFE9E9E9)

    // Accent
    /**
     * Primary accent — dark 0x008CFF / light 0x0057E0.
     *
     * Adaptive, and it has to be: a single hex cannot clear 4.5:1 as text in both themes, and
     * accent *is* text here — `CTSettingsRow` action rows, `ConstructActionRow`, the onboarding
     * copy. The value this replaced, 0x0062FF, read 3.97 on [bg]: below the bar everywhere it was
     * used as text, and below it under a filled button's own label, which draws in [bg] (`onPrimary`) on this fill.
     *
     * 0x008CFF is 5.87 on [bg] and 4.81 on a [bgMsg] card; 0x0057E0 is 5.45 / 4.71 on the light
     * pair. `CTColorContrastTest` measures all four — do not eyeball a replacement.
     */
    val accentDark = Color(0xFF008CFF)
    val accentLight = Color(0xFF0057E0)

    /**
     * Pressed / hover partner of [accent] — dark 0x0077DB / light 0x0047B3.
     *
     * **Fill, hover and status dots only — never text.** No dim partner of this accent reaches
     * 4.5:1 on [bg] (0x0077DB peaks at 4.42), so text that used to be `accentDim` is [accent]
     * instead. `CTColorContrastTest` asserts the 4.42, so the rule has its evidence beside it
     * rather than only in this comment.
     */
    val accentDimDark = Color(0xFF0077DB)
    val accentDimLight = Color(0xFF0047B3)

    // Text
    val textDark = Color(0xFFE8E8E8)        // dark 0xE8E8E8 / light 0x111111
    val textLight = Color(0xFF111111)
    /**
     * Timestamps, metadata, inactive — dark 0x8A8A8A / light 0x333333.
     *
     * The dark value was 0x818181: 5.11 on [bg] but 4.18 on a [bgMsg] card, which is where most
     * secondary text sits. iOS moved to 0x8A8A8A for that reason; `CTColorContrastTest` measures it.
     */
    val textDimDark = Color(0xFF8A8A8A)
    val textDimLight = Color(0xFF333333)
    val outMsgTextDark = Color(0xFFFFFFFF)  // dark #FFFFFF / light #111111
    val outMsgTextLight = Color(0xFF111111)

    // Structure (separators, ASCII noise)
    val noiseDark = Color(0xFF1E1E1E)       // dark 0x1E1E1E / light 0xC8C8C8
    val noiseLight = Color(0xFFC8C8C8)


    // Danger — one value in both themes; the [danger] token reads it through the `error` role.
    val dangerFixed = Color(0xFFDC3C3C)

    /**
     * Warning, debug chrome, the light-theme sun — iOS writes these as SwiftUI `.orange`, which is
     * the system orange: dark 0xFF9F0A / light 0xFF9500. Not text on a card; a status tint.
     */
    val warningDark = Color(0xFFFF9F0A)
    val warningLight = Color(0xFFFF9500)

    /**
     * Connected and holding: the connection dot and the network status — dark 0x30D158 / light
     * 0x248A3D, the platform green and its high-contrast variant. The light value is the
     * accessible one: a dot on [bgLight] needs 3:1, and 0x34C759 gives about 2.0 (0x248A3D: 3.9).
     * **Status only — never a control or text.** **Canon:** iOS `Color.CT.online`.
     */
    val onlineDark = Color(0xFF30D158)
    val onlineLight = Color(0xFF248A3D)

    /*
     * Over media — the call screens and media overlays sit on a picture, not on the theme's
     * ground, so these do not follow the theme. **Canon:** iOS `Color.CT.onMedia` and the rest.
     */
    /** Text and glyphs over a picture. */
    val onMedia = Color.White
    /** Secondary text over a picture. */
    val onMediaDim = Color.White.copy(alpha = 0.8f)
    /** Darkening laid over a picture so text and controls read on it. */
    val mediaScrim = Color.Black.copy(alpha = 0.45f)
    /** A round control's disc over a picture, off. */
    val mediaControl = Color.White.copy(alpha = 0.14f)
    /** A round control's disc over a picture, on. */
    val mediaControlOn = Color.White
    /** The answer button of an incoming call: the platform's green, a control by convention. */
    val answer = Color(0xFF30D158)

    /** The ground behind full-screen media and the camera: the viewer, the QR scanner, the crop, the recording. */
    val mediaGround = Color.Black
    /** A chip on a picture — a duration, the play and download pills (iOS writes `.black.opacity(0.55)`). */
    val mediaBadge = Color.Black.copy(alpha = 0.55f)
    /** The glyph on a lit control over media — muted, camera off: dark on [mediaControlOn]. */
    val onMediaControlOn = Color.Black

    /** Text and glyphs on a filled accent or danger control: swipe actions, a destructive button, call discs. */
    val onFill = Color.White

    /** The dim laid over the app while a palette has the screen. Not over media: that is [mediaScrim]. */
    val scrim = Color.Black.copy(alpha = 0.45f)

    /** The delivered checkmark: the platform green, as iOS `.green`. Status only. */
    val delivered = Color(0xFF30D158)

    /** A QR code stays dark on white whatever the theme — that is what scanners read. */
    val qrPaper = Color.White
    val qrInk = Color.Black
    /** The far end of the logo's gradient in the middle of a QR code. */
    val qrInkSoft = Color(0xFF4A4A4A)
}
