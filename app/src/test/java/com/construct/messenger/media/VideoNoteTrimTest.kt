package com.construct.messenger.media

import org.junit.Assert.assertEquals
import org.junit.Test

/** **Canon:** iOS `VideoNoteTrimTests` — handle limits — and how the kept stretch falls on segments. */
class VideoNoteTrimTest {

    @Test
    fun `a handle stays inside the recording`() {
        assertEquals(0L..10_000L, VideoNoteTrim.moved(true, -500, 2_000L..10_000L, 10_000))
        assertEquals(0L..10_000L, VideoNoteTrim.moved(false, 12_000, 0L..8_000L, 10_000))
    }

    /** Mutation: let the handles meet — a note of nothing could be sent. */
    @Test
    fun `the handles stay a second apart`() {
        assertEquals(4_000L..5_000L, VideoNoteTrim.moved(true, 4_800, 0L..5_000L, 10_000))
        assertEquals(4_000L..5_000L, VideoNoteTrim.moved(false, 4_100, 4_000L..10_000L, 10_000))
    }

    @Test
    fun `a recording shorter than a second keeps all of itself`() {
        assertEquals(0L..600L, VideoNoteTrim.moved(true, 300, 0L..600L, 600))
    }

    @Test
    fun `no trim keeps every segment whole`() {
        assertEquals(listOf(0 to 0L..3_000L, 1 to 0L..2_000L), VideoNoteTrim.clips(listOf(3_000, 2_000), null))
    }

    /** Mutation: clip each segment by the joined times — the second segment's part lands wrong. */
    @Test
    fun `a stretch across a pause is cut from both segments`() {
        assertEquals(listOf(0 to 1_000L..3_000L, 1 to 0L..1_500L), VideoNoteTrim.clips(listOf(3_000, 2_000), 1_000L..4_500L))
        assertEquals(listOf(1 to 500L..1_500L), VideoNoteTrim.clips(listOf(3_000, 2_000), 3_500L..4_500L))
    }

    @Test
    fun `the kept length is what is sent`() {
        val take = VideoNoteTake(listOf(VideoNoteTake.Segment(java.io.File("a"), 3_000), VideoNoteTake.Segment(java.io.File("b"), 2_000)), 1_000L..4_500L)
        assertEquals(5_000, take.durationMs)
        assertEquals(3_500, take.keptMs)
    }

    @Test
    fun `only a source faster than 30 fps is thinned`() {
        assertEquals(false, VideoEncoding.needsFrameCap(null))
        assertEquals(false, VideoEncoding.needsFrameCap(30f))
        assertEquals(true, VideoEncoding.needsFrameCap(60f))
    }
}
