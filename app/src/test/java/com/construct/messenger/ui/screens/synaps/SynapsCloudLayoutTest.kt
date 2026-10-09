package com.construct.messenger.ui.screens.synaps

import com.construct.messenger.data.model.ChatActivity
import com.construct.messenger.ui.screens.synaps.SynapsCloudLayout.PITCH
import com.construct.messenger.ui.screens.synaps.SynapsCloudLayout.ROW_STRETCH
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The cloud's layout and lens, as iOS `SynapsCloudLayoutTests` holds them (vault TODO 133): the
 * same numbers on both platforms are what makes the cloud look the same.
 */
class SynapsCloudLayoutTest {

    private fun dist(a: SynapsCloudLayout.Point, b: SynapsCloudLayout.Point) = hypot(a.x - b.x, a.y - b.y)
    private fun radius(p: SynapsCloudLayout.Point) = hypot(p.x, p.y)

    @Test
    fun noTwoCellsCloserThanPitch() {
        val cells = SynapsCloudLayout.spiral(127)
        assertEquals(127, cells.size)
        for (i in cells.indices) for (j in i + 1 until cells.size) {
            assertTrue("cells $i and $j are ${dist(cells[i], cells[j])} apart", dist(cells[i], cells[j]) >= PITCH - 0.01f)
        }
    }

    @Test
    fun ringsOfOneSixTwelve() {
        val cells = SynapsCloudLayout.spiral(19)
        assertEquals(SynapsCloudLayout.Point(0f, 0f), cells[0])
        val ring1 = cells.subList(1, 7)
        val ring2 = cells.subList(7, 19)
        assertTrue(ring1.all { radius(it) <= PITCH * ROW_STRETCH + 0.01f })
        assertTrue("ring 2 lies wholly beyond ring 1", ring2.minOf { radius(it) } > ring1.maxOf { radius(it) })
    }

    @Test
    fun radiusGrowsAsSquareRootOfCount() {
        val r100 = SynapsCloudLayout.spiral(100).maxOf { radius(it) }
        val r25 = SynapsCloudLayout.spiral(25).maxOf { radius(it) }
        assertTrue(r100 < PITCH * maxOf(1f, ROW_STRETCH) * 7)
        assertEquals(2.0, (r100 / r25).toDouble(), 0.5)
    }

    @Test
    fun mostActiveTakesTheCentre() {
        val metrics = mapOf(
            "a" to ContactMetrics(frequencyScore = 0.2f),
            "b" to ContactMetrics(frequencyScore = 1f),
            "c" to ContactMetrics(frequencyScore = 0.5f),
        )
        assertEquals(listOf("b", "c", "a"), SynapsCloudLayout.order(listOf("a", "b", "c"), metrics))
    }

    @Test
    fun equalActivityOrdersByIdWhateverTheInputOrder() {
        val ids = listOf("d", "a", "c", "b")
        val one = SynapsCloudLayout.order(ids, emptyMap())
        val two = SynapsCloudLayout.order(ids.reversed(), emptyMap())
        assertEquals(listOf("a", "b", "c", "d"), one)
        assertEquals(one, two)
    }

    @Test
    fun lensDrawsNothingBeyondTheOval() {
        val rx = 180f
        val ry = 320f
        val lens = SynapsLens(rx, ry)
        for (deg in 0 until 360 step 15) {
            val a = Math.toRadians(deg.toDouble())
            for (d in listOf(1f, 50f, 200f, 1_000f, 10_000f)) {
                val p = lens.draw((d * cos(a)).toFloat(), (d * sin(a)).toFloat(), 0f, 0f)
                val rim = hypot(p.x / rx, p.y / ry)
                assertTrue("drawn at rim $rim for $deg° $d", rim < 1f + 1e-4f)
            }
        }
    }

    @Test
    fun lensLeavesTheMiddleAndGrowsMonotonically() {
        val lens = SynapsLens(180f, 320f)
        val near = lens.draw(5f, 0f, 0f, 0f)
        assertEquals(5f, near.x, 0.05f)
        var last = 0f
        for (d in 1..2_000 step 7) {
            val rim = lens.draw(d.toFloat(), 0f, 0f, 0f).rim
            assertTrue(rim >= last)
            last = rim
        }
    }

    @Test
    fun scaleAndOpacityAtTheRim() {
        assertEquals(1f, SynapsLens.scale(0f), 1e-4f)
        assertEquals(SynapsLens.RIM_SCALE, SynapsLens.scale(1f), 1e-4f)
        assertEquals(1f, SynapsLens.opacity(0.7f), 1e-4f)
        assertEquals(SynapsLens.RIM_OPACITY, SynapsLens.opacity(1f), 1e-4f)
    }

    @Test
    fun onlyTheCentreAndFirstRingAreNamed() {
        assertEquals(1f, SynapsLens.labelOpacity(PITCH), 1e-4f)
        assertEquals(0f, SynapsLens.labelOpacity(2 * PITCH), 1e-4f)
    }

    @Test
    fun metricsFromChats() {
        val now = 10 * 86_400_000L
        val chats = listOf(
            ChatActivity("a", messages = 10, lastMessageTime = now - 1_000, unreadCount = 2),
            ChatActivity("a", messages = 4, lastMessageTime = now - 3 * 86_400_000L, unreadCount = 1),
            ChatActivity("b", messages = 5, lastMessageTime = now - 3 * 86_400_000L, unreadCount = 0),
        )
        val m = ContactMetrics.byContact(listOf("a", "b", "c"), chats, now)
        assertEquals(1f, m.getValue("a").frequencyScore, 1e-4f)
        assertEquals(0.5f, m.getValue("b").frequencyScore, 1e-4f)
        assertEquals(0f, m.getValue("c").frequencyScore, 1e-4f)
        assertEquals(ContactMetrics.Recency.FRESH, m.getValue("a").recency)
        assertEquals(ContactMetrics.Recency.RECENT, m.getValue("b").recency)
        assertEquals(ContactMetrics.Recency.NONE, m.getValue("c").recency)
        assertEquals(3, m.getValue("a").unreadCount)
    }
}
