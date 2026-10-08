package com.construct.messenger.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Layout / geometry / spacing tokens.
 *
 * `CTLayout`, `CTIcon` and `CTSpace` mirror iOS `ConstructTheme.swift` exactly;
 * `CornerRadius` is the Android-side scale (`docs/ANDROID_ONBOARDING.md` §3).
 * Keep usages on the scale instead of magic numbers — `scripts/check_ui_tokens.sh`
 * counts what is not, and fails when the count rises.
 */

/** Nav bar / tab bar metrics — must match iOS `CTLayout`. */
object CTLayout {
    val edgePad = CTSpace.m    // horizontal padding
    val navVPad = 11.dp        // vertical padding of nav bar
    val navBarHeight = 44.dp   // fixed nav bar height
    val navIconSize = CTIcon.nav     // icon size for nav bar buttons
    val navIconSizeLg = CTIcon.navLg // larger nav icon variant
    val chromeGap = 10.dp      // gap between chrome elements; compact row padding
    val inlinePad = CTSpace.s  // inline padding inside a row
    val sectionGap = CTSpace.l // between sections
    val controlHeight = 42.dp  // minimum height of a tappable card row
    val hitTarget = 44.dp      // smallest thing meant to be tapped
    val callIconSize = CTIcon.control // glyph inside a call control
    val callControlSize = 56.dp // mute / speaker discs
    val callEndSize = 64.dp    // the end-call disc
}

/**
 * Icon sizes — seven steps instead of a size per site. **Canon:** iOS `CTIcon`. A size between two
 * steps rounds to the nearer one, up on a tie (owner's choice, 2026-10-06).
 */
object CTIcon {
    /** Beside a caption: chips, status, a lock in a row. */
    val caption = 12.dp
    /** In a list row, beside the main text. */
    val row = 16.dp
    /** Actions in the nav bar. */
    val nav = 20.dp
    /** An emphasised action, the chat header's button. */
    val navLg = 22.dp
    /** Inside a round control: call buttons, the media viewer's bar. */
    val control = 24.dp
    /** Over media: play, download, retry. */
    val overlay = 32.dp
    /** An empty screen, a screen's single symbol. */
    val hero = 48.dp
}

/** Spacing scale. **Canon:** iOS `CTSpace`. */
object CTSpace {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

/** Corner radii. */
object CornerRadius {
    val small = 8.dp        // cards, badges, buttons, section groups
    val control = 10.dp     // iOS CTRadius.control — media tiles, video notes
    val medium = 12.dp
    val large = 16.dp       // message bubbles
    val extraLarge = 20.dp
    val bubble = 10.dp      // iOS message bubble / input field
    val badge = 6.dp        // unread badges
}

/** Hairline border width used across components (iOS uses 0.5pt). */
val HairlineBorder = 0.5.dp
