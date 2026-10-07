package com.construct.messenger.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.construct.messenger.ui.theme.CTSpace

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

/**
 * 64-bit FNV-1a hash over the UTF-8 bytes of [seed]. Distinct from the DJB2 hash used
 * for the accent colour, so the identicon pattern is not correlated with the hue.
 *
 * **Canon:** iOS `IdenticonView.patternHash` (offset basis `0xcbf29ce484222325`,
 * prime `0x100000001b3`).
 */
fun identiconPatternHash(seed: String): ULong {
    var hash = 0xcbf2_9ce4_8422_2325uL
    for (b in seed.toByteArray(Charsets.UTF_8)) {
        hash = hash xor (b.toUInt() and 0xFFu).toULong()
        hash *= 0x0000_0100_0000_01b3uL
    }
    return hash
}

/**
 * The symmetric on/off grid for [seed]'s identicon. Only the left half (incl. the centre
 * column) is hashed; the right half mirrors it. Each cell consumes a 4-bit nibble of
 * [identiconPatternHash] and is filled when that nibble `>= densityThreshold`.
 *
 * **Canon:** iOS `IdenticonView.cells` (gridSize 5, densityThreshold 8).
 */
fun identiconCells(
    seed: String,
    gridSize: Int = 5,
    densityThreshold: ULong = 8uL,
): Array<BooleanArray> {
    val cols = gridSize
    val halfCols = (cols + 1) / 2
    val hash = identiconPatternHash(seed)
    val grid = Array(gridSize) { BooleanArray(cols) }
    // 64 bits / 4-bit nibbles = 16 cells available; a 5×5 grid hashes 15 (3×5), fits.
    var nibble = 0
    for (r in 0 until gridSize) {
        for (c in 0 until halfCols) {
            val value = (hash shr ((nibble % 16) * 4)) and 0xFuL
            val on = value >= densityThreshold
            grid[r][c] = on
            grid[r][cols - 1 - c] = on
            nibble += 1
        }
    }
    return grid
}

/**
 * Deterministic dot-matrix identicon generated from a stable [seed] (userId / UUID).
 * 5×5 mirrored grid of accent-coloured dots, drawn in a single [Canvas]. The 14% inset
 * keeps corner dots inside the circular clip.
 *
 * **Canon:** iOS `MainAvatarView.swift` → `struct IdenticonView`.
 */
@Composable
fun IdenticonView(seed: String, modifier: Modifier = Modifier) {
    val accent = hexagonAccent(seed)
    val grid = identiconCells(seed)
    val gridSize = grid.size
    Canvas(modifier = modifier) {
        val side = this.size.minDimension
        val inset = side * 0.14f
        val cell = (side - inset * 2f) / gridSize
        val dotRadius = cell * 0.34f
        for (r in 0 until gridSize) {
            for (c in 0 until gridSize) {
                if (!grid[r][c]) continue
                val cx = inset + (c + 0.5f) * cell
                val cy = inset + (r + 0.5f) * cell
                drawCircle(
                    color = accent,
                    radius = dotRadius,
                    center = Offset(cx, cy),
                )
            }
        }
    }
}

/**
 * Circular avatar with a deterministic accent ring.
 *
 * **Canon:** iOS `MainAvatarView`.
 * - Color from [hexagonAccent] of [userId]. [image] if provided, else a generated
 *   dot-matrix [IdenticonView] (seeded by [userId]) on a 12%-accent tint.
 * - Accent stroke ring (opacity 1.0 when [isActive], else 0.45). When active, an extra
 *   wider faint glow ring.
 */
@Composable
fun CTAvatar(
    userId: String,
    modifier: Modifier = Modifier,
    displayName: String = "",
    image: ImageBitmap? = null,
    size: Dp = 44.dp,
    isActive: Boolean = false,
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
                    contentDescription = displayName.ifEmpty { userId },
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(size),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(size)
                        .background(accent.copy(alpha = 0.12f))
                        .semantics { contentDescription = displayName.ifEmpty { userId } },
                    contentAlignment = Alignment.Center,
                ) {
                    IdenticonView(seed = userId, modifier = Modifier.size(size))
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
    }
}

@Preview(backgroundColor = 0xFF090909, showBackground = true)
@Composable
private fun CTAvatarPreview() {
    androidx.compose.foundation.layout.Column(
        verticalArrangement = Arrangement.spacedBy(20.dp),
        modifier = Modifier.padding(CTSpace.l),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(CTSpace.l)) {
            CTAvatar(userId = "alice-123", displayName = "Alice", size = 52.dp, isActive = true)
            CTAvatar(userId = "bob-456", displayName = "Bob Smith", size = 52.dp)
            CTAvatar(userId = "carol-789", displayName = "Carol", size = 52.dp)
            CTAvatar(userId = "dave-000", displayName = "", size = 52.dp)
        }
        // Small (chat-list) size — identicons stay distinct at 36dp.
        Row(horizontalArrangement = Arrangement.spacedBy(CTSpace.m)) {
            for (id in listOf("u1", "u2", "u3", "u4", "u5")) {
                CTAvatar(userId = id, displayName = id.uppercase(), size = 36.dp)
            }
        }
    }
}
