package com.construct.messenger.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.construct.messenger.ui.theme.CTColor

/**
 * Deterministic accent color derived from a stable id (userId).
 *
 * **Canon:** iOS `MainAvatarView.swift` → `Color.hexagonAccent(for:)`.
 * djb2 hash (UInt32, wrapping) over Unicode code points → HSV(hue, 0.60, 0.55).
 * NB: iOS uses HSB/HSV — not HSL (the `ANDROID_ONBOARDING.md` §4.10 draft said
 * `Color.hsl`, which would give different colors).
 */
fun hexagonAccent(userId: String): Color =
    Color.hsv(hexagonHue(userId).toFloat(), 0.60f, 0.55f)

/**
 * The deterministic hue in degrees `[0,360)` for [userId] — the cross-platform value
 * (iOS computes the same `hash % 360`). Extracted so it can be golden-tested without
 * depending on HSV→sRGB rounding.
 */
fun hexagonHue(userId: String): Int {
    var hash = 5381u
    var i = 0
    while (i < userId.length) {
        val cp = userId.codePointAt(i)
        hash = (hash shl 5) + hash + cp.toUInt()
        i += Character.charCount(cp)
    }
    return (hash % 360u).toInt()
}

/** First-letter(s) of the display name, uppercased. iOS `MainAvatarView.initials`. */
private fun initialsOf(displayName: String): String {
    val words = displayName.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when (words.size) {
        0 -> "?"
        1 -> words[0].take(2).uppercase()
        else -> (words[0].take(1) + words[1].take(1)).uppercase()
    }
}

/**
 * Circular avatar with a deterministic accent ring.
 *
 * **Canon:** iOS `MainAvatarView`.
 * - Color from [hexagonAccent] of [userId]. [image] if provided, else initials of
 *   [displayName] on a 12%-accent tint.
 * - Accent stroke ring (opacity 1.0 when [isActive], else 0.45). When active, an extra
 *   wider faint glow ring. Green presence dot at bottom-trailing when [isOnline].
 */
@Composable
fun CTAvatar(
    userId: String,
    modifier: Modifier = Modifier,
    displayName: String = "",
    image: ImageBitmap? = null,
    size: Dp = 44.dp,
    isActive: Boolean = false,
    isOnline: Boolean = false,
    strokeWidth: Dp = 1.5.dp,
) {
    val accent = hexagonAccent(userId)

    Box(modifier = modifier.size(size), contentAlignment = Alignment.BottomEnd) {
        // Fill + ring, clipped to a circle.
        Box(
            modifier = Modifier.size(size).clip(CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(size),
                )
            } else {
                Box(
                    modifier = Modifier.size(size).background(accent.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = initialsOf(displayName),
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium,
                            fontSize = (size.value * 0.33f).sp,
                        ),
                        color = accent,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }

            Canvas(modifier = Modifier.size(size)) {
                val sw = strokeWidth.toPx()
                drawCircle(
                    color = accent.copy(alpha = if (isActive) 1.0f else 0.45f),
                    radius = (this.size.minDimension - sw) / 2f,
                    style = Stroke(width = sw),
                )
                if (isActive) {
                    val gw = 3.dp.toPx()
                    drawCircle(
                        color = accent.copy(alpha = 0.25f),
                        radius = (this.size.minDimension - gw) / 2f,
                        style = Stroke(width = gw),
                    )
                }
            }
        }

        if (isOnline) {
            Box(
                modifier = Modifier
                    .offset(x = 2.dp, y = 2.dp)
                    .size(size * 0.22f)
                    .clip(CircleShape)
                    .background(Color(0xFF34C759))
                    .border(1.5.dp, CTColor.bg, CircleShape),
            )
        }
    }
}

@Preview(backgroundColor = 0xFF090909, showBackground = true)
@Composable
private fun CTAvatarPreview() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.padding(16.dp),
    ) {
        CTAvatar(userId = "alice-123", displayName = "Alice", size = 52.dp, isActive = true, isOnline = true)
        CTAvatar(userId = "bob-456", displayName = "Bob Smith", size = 52.dp)
        CTAvatar(userId = "carol-789", displayName = "Carol", size = 52.dp, isOnline = true)
        CTAvatar(userId = "dave-000", displayName = "", size = 52.dp)
    }
}
