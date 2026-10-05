package com.construct.messenger.media

import android.view.TextureView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.construct.messenger.test.MainDispatcherRule
import com.construct.messenger.ui.components.VideoNoteLayout
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The video note that plays with sound, expanded in place ([VideoNotePlayback]). Each test names
 * the mutation that reddens it. **Canon:** iOS `VideoNotePlaybackTests`.
 */
class VideoNotePlaybackTest {

    /** VoicePlayer's ticker runs on Main; a dispatcher nobody advances keeps it still. */
    @get:Rule
    val main = MainDispatcherRule(StandardTestDispatcher())

    private class FakeSession(val key: VideoNotePlayback.Key, val onEnd: () -> Unit) : VideoNotePlayback.Session {
        var playing = true
        var speed = 1f
        var released = false
        override fun pause() { playing = false }
        override fun resume(rate: Float) { playing = true; speed = rate }
        override fun setRate(rate: Float) { speed = rate }
        override fun attach(view: TextureView) = Unit
        override fun detach(view: TextureView) = Unit
        override fun release() { released = true }
    }

    private val sessions = mutableListOf<FakeSession>()
    private val engine = object : VideoNotePlayback.Engine {
        override fun open(
            data: ByteArray,
            key: VideoNotePlayback.Key,
            rate: Float,
            onProgress: (Float) -> Unit,
            onEnd: () -> Unit,
        ): VideoNotePlayback.Session = FakeSession(key, onEnd).also { it.speed = rate; sessions += it }
    }
    private val focus = SoundFocus()
    private val playback = VideoNotePlayback(engine, focus)
    private val voice by lazy { VoicePlayer(focus) } // after the rule has set Main

    /**
     * The real [VoicePlayer], so the claim it makes is what is tested. Android's stub builder
     * returns null from each setter; this one returns itself.
     */
    private fun <T> withVoice(block: () -> T): T =
        org.mockito.Mockito.mockConstruction(
            android.media.AudioAttributes.Builder::class.java,
            org.mockito.Mockito.withSettings().defaultAnswer(org.mockito.Answers.RETURNS_SELF),
        ).use { block() }
    private val a = VideoNotePlayback.Key("a")
    private val b = VideoNotePlayback.Key("b")
    private val bytes = ByteArray(1)

    /** Mutation: make a second tap re-expand — the note restarts instead of pausing. */
    @Test
    fun `taps expand, then pause, then resume`() {
        playback.tap(a, bytes)
        assertEquals(a, playback.state.value.expanded)
        assertFalse(playback.state.value.paused)

        playback.tap(a, bytes)
        assertEquals("a pause keeps the note expanded", a, playback.state.value.expanded)
        assertTrue(playback.state.value.paused)
        assertFalse(sessions.single().playing)

        playback.tap(a, bytes)
        assertFalse(playback.state.value.paused)
        assertTrue(sessions.single().playing)
    }

    /** Mutation: drop the `collapse()` at the top of `expand` — two notes play over each other. */
    @Test
    fun `only one note is expanded`() {
        playback.tap(a, bytes)
        playback.tap(b, bytes)
        assertEquals(b, playback.state.value.expanded)
        assertTrue("the first note's player is gone", sessions[0].released)
        assertFalse(sessions[1].released)
    }

    @Test
    fun `scrolling another note away leaves this one playing`() {
        playback.tap(a, bytes)
        playback.collapse(ifShowing = b)
        assertEquals(a, playback.state.value.expanded)
        playback.collapse(ifShowing = a)
        assertNull(playback.state.value.expanded)
        assertTrue(sessions.single().released)
    }

    /** Mutation: remove `focus.claim` from `VoicePlayer.toggle` — the voice and the note play together. */
    @Test
    fun `a voice message folds the note back`() {
        playback.tap(a, bytes)
        withVoice { voice.toggle("voice", ByteArray(0)) }
        assertNotNull("the voice note plays", voice.state.value)
        assertNull(playback.state.value.expanded)
        assertTrue(sessions.single().released)
        voice.stop()
    }

    /** Mutation: remove `focus.claim` from `expand` — the voice keeps playing under the note. */
    @Test
    fun `expanding a note stops the voice message`() {
        withVoice { voice.toggle("voice", ByteArray(0)) }
        assertNotNull(voice.state.value)
        playback.tap(a, bytes)
        assertNull(voice.state.value)
        assertEquals(a, playback.state.value.expanded)
    }

    @Test
    fun `the end folds the note back`() {
        playback.tap(a, bytes)
        sessions.single().onEnd()
        assertNull(playback.state.value.expanded)
        assertTrue(sessions.single().released)
    }

    /** Mutation: keep the rate across `collapse` — the next note starts fast. */
    @Test
    fun `the speed cycles and resets on collapse`() {
        playback.tap(a, bytes)
        assertEquals(1f, playback.state.value.rate)
        playback.cycleRate()
        assertEquals(1.5f, playback.state.value.rate)
        assertEquals(1.5f, sessions.single().speed)
        playback.cycleRate()
        assertEquals(2f, playback.state.value.rate)
        playback.cycleRate()
        assertEquals(1f, playback.state.value.rate)
        playback.cycleRate()
        playback.collapse()
        assertEquals("the next note starts at normal speed", 1f, playback.state.value.rate)
        playback.tap(b, bytes)
        assertEquals(1f, sessions.last().speed)
    }

    @Test
    fun `rate labels`() {
        assertEquals("1×", VideoNotePlayback.rateLabel(1f))
        assertEquals("1.5×", VideoNotePlayback.rateLabel(1.5f))
        assertEquals("2×", VideoNotePlayback.rateLabel(2f))
    }

    /**
     * The expanded note leaves the opposite gutter free (the chat stays visible) and never shrinks
     * below the collapsed size, whatever the row measured.
     */
    @Test
    fun `the expanded width`() {
        val phone = VideoNoteLayout.expandedWidth(393.dp)
        assertTrue(phone > VideoNoteLayout.WIDTH)
        assertTrue(phone <= 393.dp - VideoNoteLayout.SIDE_GUTTER)

        assertEquals(VideoNoteLayout.MAX_EXPANDED_WIDTH, VideoNoteLayout.expandedWidth(1366.dp))
        assertEquals(VideoNoteLayout.WIDTH, VideoNoteLayout.expandedWidth(100.dp))
        assertEquals(
            VideoNoteLayout.expandedWidth(VideoNoteLayout.DEFAULT_ROW_WIDTH),
            VideoNoteLayout.expandedWidth(Dp.Unspecified),
        )
        assertEquals(
            VideoNoteLayout.expandedWidth(VideoNoteLayout.DEFAULT_ROW_WIDTH),
            VideoNoteLayout.expandedWidth(0.dp),
        )
    }
}
