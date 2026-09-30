package com.construct.messenger.ui.components

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.construct.messenger.ui.theme.CTColor
import kotlin.random.Random

private const val MATRIX_CHARS = "01ABCDEFabcdef><[]{}|~."
private val MATRIX_CELL = 36.dp
private const val MATRIX_OPACITY = 0.05f

/**
 * The lock screen's lattice: one glyph centred in every 36 dp cell, 9 sp, `text` at 5 %.
 *
 * **Canon:** iOS `LatticeBackgroundView.swift` → `struct CTMatrixBackground`. Not [CTNoise] —
 * that one is a denser grid of other glyphs pinned to the cell corner; the lock screen has its
 * own, sparser and hex-flavoured.
 */
@Composable
fun CTMatrixBackground(modifier: Modifier = Modifier) {
    // Enough cells for any phone; each draw uses only the ones that fit.
    val grid = remember { Array(64) { CharArray(32) { MATRIX_CHARS[Random.nextInt(MATRIX_CHARS.length)] } } }
    Box(
        modifier = modifier.drawWithCache {
            val cell = MATRIX_CELL.toPx()
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = CTColor.text.copy(alpha = MATRIX_OPACITY).toArgb()
                typeface = Typeface.MONOSPACE
                textSize = 9.sp.toPx()
                textAlign = Paint.Align.CENTER
            }
            val fm = paint.fontMetrics
            val baseline = -(fm.ascent + fm.descent) / 2
            val rows = minOf(grid.size, (size.height / cell).toInt() + 1)
            val cols = minOf(grid[0].size, (size.width / cell).toInt() + 1)
            onDrawBehind {
                val canvas = drawContext.canvas.nativeCanvas
                for (r in 0 until rows) {
                    for (c in 0 until cols) {
                        canvas.drawText(grid[r][c].toString(), c * cell + cell / 2, r * cell + cell / 2 + baseline, paint)
                    }
                }
            }
        },
    )
}
