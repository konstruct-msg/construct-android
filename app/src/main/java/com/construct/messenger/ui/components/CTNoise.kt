package com.construct.messenger.ui.components

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.KonstructMessengerTheme
import com.construct.messenger.ui.theme.LocalIsDarkTheme
import kotlin.random.Random

/**
 * iOS passes 0.10 / 0.06, but measured on screen its glyphs land at ~21 on the dark background
 * (9) — about 0.6 of the way to `noise`. These are the values that reproduce what iOS shows, not
 * the numbers it passes.
 */
private const val NOISE_OPACITY_DARK = 0.6f
private const val NOISE_OPACITY_LIGHT = 0.06f

/** ASCII glyphs for the noise texture — canon §4.14 / iOS `CTNoise`. */
internal val NOISE_CHARS = charArrayOf(
    '@', '%', '#', '+', '-', '=', ':', '.', '*', '/', '\\', '|', '~', '^', '<', '>',
)

@Composable
private fun Modifier.ctNoiseOverlay(
    rows: Int = 40,
    cols: Int = 22,
    opacity: Float = if (LocalIsDarkTheme.current) NOISE_OPACITY_DARK else NOISE_OPACITY_LIGHT,
): Modifier {
    val dark = LocalIsDarkTheme.current
    val noiseColor = if (dark) CTColor.noise else CTColor.noiseLight
    val grid = remember(rows, cols) {
        Array(rows) { CharArray(cols) { NOISE_CHARS[Random.nextInt(NOISE_CHARS.size)] } }
    }

    return drawWithCache {
        val cellW = size.width / cols
        val cellH = size.height / rows
        // iOS draws each glyph in `CTFont.mono(10)` at the cell's top-leading corner.
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = noiseColor.copy(alpha = opacity).toArgb()
            typeface = Typeface.MONOSPACE
            this.textSize = 10.sp.toPx()
            textAlign = Paint.Align.LEFT
        }
        val fm = paint.fontMetrics
        onDrawBehind {
            val canvas = drawContext.canvas.nativeCanvas
            for (r in 0 until rows) {
                val y = cellH * r - fm.ascent
                for (c in 0 until cols) {
                    canvas.drawText(
                        grid[r][c].toString(),
                        cellW * c,
                        y,
                        paint,
                    )
                }
            }
        }
    }
}

/**
 * Random ASCII grid at low opacity — textured background layer.
 *
 * **Canon:** iOS `ConstructTheme.swift` → `struct CTNoise` (§4.14).
 * Deterministic seed is not required; grid is generated once per composition.
 */
@Composable
fun CTNoise(
    modifier: Modifier = Modifier,
    rows: Int = 40,
    cols: Int = 22,
    opacity: Float = if (LocalIsDarkTheme.current) NOISE_OPACITY_DARK else NOISE_OPACITY_LIGHT,
) {
    Box(modifier = modifier.ctNoiseOverlay(rows, cols, opacity))
}

/** Solid `bg` token plus [CTNoise] overlay — iOS `.ctBackground()`. */
@Composable
fun Modifier.ctBackground(
    rows: Int = 40,
    cols: Int = 22,
): Modifier {
    val dark = LocalIsDarkTheme.current
    val bg = if (dark) CTColor.bg else CTColor.bgLight
    val opacity = if (dark) 0.10f else 0.06f
    return background(bg).ctNoiseOverlay(rows, cols, opacity)
}

@Preview(backgroundColor = 0xFF090909, showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun CTNoisePreview() {
    KonstructMessengerTheme(darkTheme = true) {
        Box(
            Modifier
                .size(360.dp, 640.dp)
                .background(CTColor.bg)
                .ctNoiseOverlay(),
        )
    }
}

@Preview(backgroundColor = 0xFFF2F2F2, showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun CTNoiseLightPreview() {
    KonstructMessengerTheme(darkTheme = false) {
        Box(
            Modifier
                .size(360.dp, 640.dp)
                .background(CTColor.bgLight)
                .ctNoiseOverlay(opacity = 0.06f),
        )
    }
}

@Preview(backgroundColor = 0xFF090909, showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun CtBackgroundPreview() {
    KonstructMessengerTheme(darkTheme = true) {
        Box(Modifier.fillMaxSize().ctBackground())
    }
}

/**
 * iOS `.ctBackground()`: the theme's background with the ASCII noise over it — the root tabs
 * (chats, Synaps, settings) sit on it.
 */
@Composable
fun Modifier.ctBackground(): Modifier = this
    .background(CTColor.bg)
    .ctNoiseOverlay()
