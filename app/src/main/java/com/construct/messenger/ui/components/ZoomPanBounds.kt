package com.construct.messenger.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/**
 * How far a zoomed picture may be pushed: never so far that its edge leaves the screen's edge, and
 * not at all on an axis where the zoomed picture is still narrower than the screen — it stays
 * centred there, as in a photo app. Past the limit, while the finger is down, it moves against a
 * rubber band and springs back on release. The same rule as iOS `ZoomPanBounds`.
 *
 * Offsets are the picture centre's from the container's, in px; the picture is drawn
 * [androidx.compose.ui.layout.ContentScale.Fit] and scaled about its centre.
 */
internal object ZoomPanBounds {
    /** The UIScrollView constant: how stiff the band is. */
    private const val RUBBER_BAND = 0.55f

    /** The size [androidx.compose.ui.layout.ContentScale.Fit] gives [image] in [container]. */
    fun fittedSize(image: Size, container: Size): Size {
        if (image.width <= 0f || image.height <= 0f) return container
        val fit = min(container.width / image.width, container.height / image.height)
        return Size(image.width * fit, image.height * fit)
    }

    /** Per axis, the furthest the centre may move at [scale]. */
    fun limit(fitted: Size, scale: Float, container: Size) = Offset(
        max(0f, (fitted.width * scale - container.width) / 2),
        max(0f, (fitted.height * scale - container.height) / 2),
    )

    fun clamped(offset: Offset, limit: Offset) = Offset(
        offset.x.coerceIn(-limit.x, limit.x),
        offset.y.coerceIn(-limit.y, limit.y),
    )

    /** Where the picture is drawn while the finger that would put it at [offset] is down. */
    fun rubberBanded(offset: Offset, limit: Offset, container: Size) = Offset(
        band(offset.x, limit.x, container.width),
        band(offset.y, limit.y, container.height),
    )

    private fun band(value: Float, limit: Float, dimension: Float): Float {
        val overshoot = abs(value) - limit
        if (overshoot <= 0f || dimension <= 0f) return value
        val resisted = (1f - 1f / (overshoot * RUBBER_BAND / dimension + 1f)) * dimension
        return sign(value) * (limit + resisted)
    }
}
