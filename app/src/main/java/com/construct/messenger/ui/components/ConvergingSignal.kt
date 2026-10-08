package com.construct.messenger.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.construct.messenger.ui.theme.CTColor
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

private const val DOTS = 24
private const val SPEED = 1.5
private const val AMPLITUDE = 0.40
private const val SETTLE_CUTOFF = 0.04
private const val DOT_BLEND_BACK = 0.08
private const val DOT_BLEND_FRONT = 0.02
private const val ENVELOPE_BLEND_FRONT = 0.06
private const val SETTLED_RADIUS = 2.2f
private const val UNSETTLED_RADIUS = 2.5f
private const val RADIUS_VARIANCE = 0.5f
private const val SETTLED_ALPHA = 0.92
private const val UNSETTLED_ALPHA_BASE = 0.25
private const val UNSETTLED_ALPHA_RANGE = 0.45
private const val FLICKER_PHASE = 0.7
private const val FLICKER_TICK = 0.9
private const val LINE_OPACITY = 0.22f
private const val LINE_WIDTH = 1.2f
private const val WAVE2_FREQ = 2.3
private const val WAVE3_FREQ = 3.7
private const val WAVE2_WEIGHT = 0.5
private const val WAVE3_WEIGHT = 0.25
private const val WAVE_NORM = 1.0 + WAVE2_WEIGHT + WAVE3_WEIGHT
private const val COLLAPSE_MS = 1200

/**
 * A jagged line of dots that settles left to right into a straight one as [progress] goes 0 → 1
 * — a signal locking in. [collapsed] pulls every dot into one bright point and fades it.
 *
 * **Canon:** iOS `ConvergingSignalView` — same constants, same three-wave jitter per dot. Sizes
 * are in dp where iOS has points.
 */
@Composable
fun ConvergingSignal(
    progress: Double,
    modifier: Modifier = Modifier,
    collapsed: Boolean = false,
    dotColor: Color = CTColor.accent,
) {
    val random = LocalDecorRandom.current
    val phases = remember { List(3) { DoubleArray(DOTS) { random.nextDouble(0.0, 2 * PI) } } }
    val collapse = remember { Animatable(0f) }
    var frameNanos by remember { mutableLongStateOf(0L) }
    val start = remember { System.nanoTime() }

    LaunchedEffect(Unit) {
        while (true) withFrameNanos { frameNanos = it }
    }
    LaunchedEffect(collapsed) {
        if (collapsed) collapse.animateTo(1f, tween(COLLAPSE_MS, easing = FastOutSlowInEasing))
    }

    Canvas(modifier) {
        val tick = (frameNanos.coerceAtLeast(start) - start) / 1e9 * SPEED
        val c = collapse.value.toDouble()
        val cx = size.width / 2f
        val cy = size.height / 2f
        val spacing = size.width / (DOTS - 1)
        val maxAmp = size.height * AMPLITUDE
        val points = List(DOTS) { i ->
            val xNormal = i * spacing
            val x = xNormal + (cx - xNormal) * c.toFloat()
            val norm = i.toDouble() / (DOTS - 1)
            val y = if (norm < progress - SETTLE_CUTOFF) {
                cy
            } else {
                val envelope = smoothstep(norm, progress - SETTLE_CUTOFF, progress + ENVELOPE_BLEND_FRONT)
                val wave = (
                    sin(phases[0][i] + tick) +
                        sin(phases[1][i] + tick * WAVE2_FREQ) * WAVE2_WEIGHT +
                        sin(phases[2][i] + tick * WAVE3_FREQ) * WAVE3_WEIGHT
                    ) / WAVE_NORM
                val normalY = cy + (maxAmp * envelope * wave).toFloat()
                normalY + (cy - normalY) * c.toFloat()
            }
            Offset(x, y)
        }
        val globalAlpha = (1.0 - c * 0.85).toFloat()

        val line = Path().apply {
            moveTo(points[0].x, points[0].y)
            for (i in 1 until DOTS) lineTo(points[i].x, points[i].y)
        }
        drawPath(line, dotColor.copy(alpha = LINE_OPACITY * globalAlpha), style = Stroke(LINE_WIDTH.dp.toPx()))

        for (i in 0 until DOTS) {
            val norm = i.toDouble() / (DOTS - 1)
            val settled = norm < progress - SETTLE_CUTOFF
            val blend = smoothstep(norm, progress - DOT_BLEND_BACK, progress + DOT_BLEND_FRONT)
            val baseR = if (settled) SETTLED_RADIUS else UNSETTLED_RADIUS - RADIUS_VARIANCE * blend.toFloat()
            val r = baseR + (SETTLED_RADIUS * 1.4f - baseR) * c.toFloat()
            val baseAlpha = if (settled) {
                SETTLED_ALPHA
            } else {
                UNSETTLED_ALPHA_BASE + UNSETTLED_ALPHA_RANGE * abs(sin(phases[0][i] * FLICKER_PHASE + tick * FLICKER_TICK))
            }
            val alpha = ((baseAlpha + (1.0 - baseAlpha) * c) * globalAlpha).toFloat()
            drawCircle(dotColor.copy(alpha = alpha.coerceIn(0f, 1f)), radius = r.dp.toPx(), center = points[i])
        }
    }
}

private fun smoothstep(x: Double, lo: Double, hi: Double): Double {
    val t = ((x - lo) / maxOf(0.001, hi - lo)).coerceIn(0.0, 1.0)
    return t * t * (3 - 2 * t)
}
