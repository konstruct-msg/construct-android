package com.construct.messenger.media

import com.construct.messenger.test.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The one track playing (TODO 149, 151). **Canon:** iOS `AudioFilePlayerTests`. */
@OptIn(ExperimentalCoroutinesApi::class)
class VoicePlayerTest {
    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val main = MainDispatcherRule(dispatcher)

    private val rate = MutableStateFlow(2f)
    private val settings = org.mockito.kotlin.mock<VoicePlaybackSettings>().also {
        org.mockito.kotlin.whenever(it.rate).thenReturn(rate)
    }
    private val engine = FakeVoiceEngine()
    private val player by lazy { VoicePlayer(SoundFocus(), settings, engine) } // after the rule has set Main
    private val song = PlaybackSource("peer", "m1", fileName = "demo.flac")
    private val note = PlaybackSource("peer", "m2")

    /** Mutation: hand the title over for a voice note too — the conversation reaches the lock screen. */
    @Test
    fun `only a music file goes to the lock screen, under its file name`() {
        player.toggle("track", ByteArray(1), music = true, source = song)
        assertEquals("demo.flac", engine.sessions.last().track.title)
        player.toggle("voice", ByteArray(1), music = false, source = note)
        assertNull(engine.sessions.last().track.title)
    }

    @Test
    fun `the lock screen title is the name without its extension`() {
        assertEquals("demo.v2", ExoVoiceEngine.lockScreenTitle("demo.v2.flac"))
        assertEquals("README", ExoVoiceEngine.lockScreenTitle("README"))
        assertEquals(".flac", ExoVoiceEngine.lockScreenTitle(".flac"))
    }

    /** Mutation: apply the voice speed to music, at start or on resume. */
    @Test
    fun `music plays at its own speed, voice at the chosen one`() {
        player.toggle("track", ByteArray(1), music = true, source = song)
        assertEquals(1f, engine.sessions.last().speed)
        player.togglePlaying()
        player.togglePlaying()
        assertEquals("resumed at its own speed", 1f, engine.sessions.last().speed)
        player.toggle("voice", ByteArray(1), source = note)
        assertEquals(2f, engine.sessions.last().speed)
    }

    @Test
    fun `the track remembers its message, and stop forgets it`() {
        player.toggle("track", ByteArray(1), music = true, source = song)
        assertEquals(song, player.state.value?.source)
        player.stop()
        assertNull(player.state.value)
        assertTrue(engine.sessions.single().released)
    }

    @Test
    fun `seeking a track not loaded starts it there`() {
        player.seek("track", ByteArray(1), 0.25f, music = true, source = song)
        assertEquals(2_500L, engine.sessions.single().positionMs)
        assertEquals(0.25f, player.state.value!!.progress)
    }

    /** Mutation: read the pause only from this class — a pause on the lock screen leaves "playing". */
    @Test
    fun `a pause from the lock screen shows in the state`() = runTest(dispatcher) {
        player.toggle("track", ByteArray(1), music = true, source = song)
        engine.sessions.single().isPlaying = false
        advanceTimeBy(100)
        assertTrue(player.state.value!!.paused)
        player.stop()
    }

    @Test
    fun `a track that plays to its end is reported and unloaded`() = runTest(dispatcher) {
        val finished = mutableListOf<String>()
        val job = launch { player.finished.collect { finished += it } }
        advanceTimeBy(1)
        player.toggle("voice", ByteArray(1), source = note)
        engine.sessions.single().onEnd()
        advanceTimeBy(1)
        assertEquals(listOf("voice"), finished)
        assertNull(player.state.value)
        job.cancel()
    }

    @Test
    fun `the banner's button pauses and resumes what is loaded, and does nothing with nothing`() {
        assertFalse(player.togglePlaying())
        player.toggle("track", ByteArray(1), music = true, source = song)
        assertTrue(player.togglePlaying())
        assertTrue(player.state.value!!.paused)
        assertTrue(player.togglePlaying())
        assertFalse(player.state.value!!.paused)
    }
}
