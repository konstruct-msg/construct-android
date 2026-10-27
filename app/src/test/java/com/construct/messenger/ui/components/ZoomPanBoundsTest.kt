package com.construct.messenger.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The same cases as iOS `ZoomPanBoundsTests`. */
class ZoomPanBoundsTest {
    private val screen = Size(400f, 800f)

    /** A landscape photo on a portrait screen: full width, a band of height. */
    private val landscape = ZoomPanBounds.fittedSize(Size(2000f, 1000f), screen)

    @Test
    fun `the fitted size is what Fit draws`() {
        assertEquals(Size(400f, 200f), landscape)
        assertEquals(Size(400f, 800f), ZoomPanBounds.fittedSize(Size(1000f, 2000f), screen))
    }

    /** Mutation: drop the `max(0, …)` — the narrow axis would slide and leave the centre. */
    @Test
    fun `an axis still narrower than the screen does not move`() {
        val limit = ZoomPanBounds.limit(landscape, 2f, screen)
        assertEquals(0f, limit.y)
        assertEquals(Offset(200f, 0f), ZoomPanBounds.clamped(Offset(500f, 300f), limit))
    }

    @Test
    fun `the limit keeps the edge on the screen's edge`() {
        // 400×200 at 3x is 1200×600: 400 either side, and still shorter than the screen.
        assertEquals(Offset(400f, 0f), ZoomPanBounds.limit(landscape, 3f, screen))
        // At 5x, 1000 high: 100 above and below.
        assertEquals(Offset(800f, 100f), ZoomPanBounds.limit(landscape, 5f, screen))
        assertEquals(Offset.Zero, ZoomPanBounds.limit(landscape, 1f, screen))
    }

    /** Mutation: return the offset unclamped — the defect, a photo pushed off the screen. */
    @Test
    fun `a release comes back inside the limit`() {
        val limit = Offset(400f, 100f)
        assertEquals(Offset(-400f, 100f), ZoomPanBounds.clamped(Offset(-900f, 250f), limit))
        assertEquals(Offset(10f, -20f), ZoomPanBounds.clamped(Offset(10f, -20f), limit))
    }

    @Test
    fun `inside the limit the picture follows the finger`() {
        val offset = Offset(-399f, 99f)
        assertEquals(offset, ZoomPanBounds.rubberBanded(offset, Offset(400f, 100f), screen))
    }

    /** Mutation: drop the band — the overshoot would follow the finger one to one. */
    @Test
    fun `past the limit the band resists and never reaches the finger`() {
        val limit = Offset(400f, 100f)
        val banded = ZoomPanBounds.rubberBanded(Offset(600f, -300f), limit, screen)
        assertTrue(banded.x > 400f && banded.x < 600f)
        assertTrue(banded.y < -100f && banded.y > -300f)
        // (1 − 1/(200·0.55/400 + 1))·400
        assertEquals(400f + 86.27451f, banded.x, 0.001f)
        // Further out is further, but never past limit + the screen's size.
        val far = ZoomPanBounds.rubberBanded(Offset(100_000f, 0f), limit, screen)
        assertTrue(far.x > banded.x && far.x < 400f + 400f)
    }
}
