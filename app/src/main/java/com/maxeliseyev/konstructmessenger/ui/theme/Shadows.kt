package com.maxeliseyev.konstructmessenger.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Shadow tokens (see `ANDROID_ONBOARDING.md` §3.6).
 *
 * Compose has no first-class shadow struct like SwiftUI's `Shadow`, so we keep
 * the parameters here and apply them at call sites (e.g. `Modifier.shadow` or a
 * custom draw). Values mirror the iOS design tokens.
 */
data class CTShadow(
    val color: Color,
    val radius: Dp,
    val offsetX: Dp,
    val offsetY: Dp,
)

object CTShadows {
    val card = CTShadow(
        color = Color.Black.copy(alpha = 0.1f),
        radius = 4.dp,
        offsetX = 0.dp,
        offsetY = 2.dp,
    )
    val inputBar = CTShadow(
        color = Color.Black.copy(alpha = 0.1f),
        radius = 2.dp,
        offsetX = 0.dp,
        offsetY = 1.dp,
    )
}
