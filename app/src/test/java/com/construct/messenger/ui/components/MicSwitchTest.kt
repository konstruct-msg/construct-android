package com.construct.messenger.ui.components

import com.construct.messenger.ui.screens.chat.VideoNoteClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** **Canon:** iOS `MicModeButton.mode(at:buttonSize:)`, turned upright — the switch grows upwards from the button. */
class MicSwitchTest {
    private val size = 28f
    private val segment = 52f
    private val margin = 44f

    private fun at(y: Float, x: Float = 14f) = MicSwitch.mode(x, y, size, segment, margin)

    @Test
    fun `the mic is under the finger where the press began`() {
        assertEquals(MicSwitch.Mode.VOICE, at(14f))
        assertEquals(MicSwitch.Mode.VOICE, at(size - segment + 1))
    }

    /** Mutation: swap the segments — sliding up would record a voice message. */
    @Test
    fun `sliding up reaches the camera`() {
        assertEquals(MicSwitch.Mode.VIDEO_NOTE, at(size - segment - 1))
        assertEquals(MicSwitch.Mode.VIDEO_NOTE, at(size - 2 * segment))
    }

    /** Releasing away from the switch starts nothing. */
    @Test
    fun `far from the switch is no choice`() {
        assertNull(at(size - 2 * segment - margin - 1))
        assertNull(at(size + margin + 1))
        assertNull(at(14f, x = -margin - 1))
        assertNull(at(14f, x = size + margin + 1))
    }

    @Test
    fun `the recording timer`() {
        assertEquals("0:00", VideoNoteClock.format(0))
        assertEquals("0:59", VideoNoteClock.format(59_999))
        assertEquals("1:00", VideoNoteClock.format(60_000))
    }
}
