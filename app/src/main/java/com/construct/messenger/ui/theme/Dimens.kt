package com.construct.messenger.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Layout / geometry / spacing tokens.
 *
 * `CTLayout` mirrors iOS `ConstructTheme.swift` → `enum CTLayout` exactly.
 * `Spacing` and `CornerRadius` are the Android-side scale (see
 * `construct-docs/raw/ANDROID_ONBOARDING.md` §3.4–3.5); keep usages on the
 * scale instead of magic numbers.
 */

/** Nav bar / tab bar metrics — must match iOS `CTLayout`. */
object CTLayout {
    val edgePad = 12.dp        // horizontal padding
    val navVPad = 11.dp        // vertical padding of nav bar
    val navBarHeight = 44.dp   // fixed nav bar height
    val navIconSize = 20.dp    // icon size for nav bar buttons
    val navIconSizeLg = 22.dp  // larger nav icon variant
    val chromeGap = 10.dp      // gap between chrome elements; compact row padding
    val inlinePad = 8.dp       // inline padding inside a row
    val sectionGap = 16.dp     // between sections
}

/** Spacing scale. */
object Spacing {
    val compact = 4.dp
    val small = 8.dp
    val standard = 12.dp
    val medium = 16.dp
    val large = 24.dp
    val extraLarge = 32.dp
}

/** Corner radii. */
object CornerRadius {
    val small = 8.dp        // cards, badges, buttons, section groups
    val control = 10.dp     // iOS CTRadius.control — CTButton
    val medium = 12.dp
    val large = 16.dp       // message bubbles
    val extraLarge = 20.dp
    val bubble = 10.dp      // iOS message bubble / input field
    val badge = 6.dp        // unread badges
}

/** Hairline border width used across components (iOS uses 0.5pt). */
val HairlineBorder = 0.5.dp
