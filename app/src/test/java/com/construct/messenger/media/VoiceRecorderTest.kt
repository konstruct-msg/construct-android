package com.construct.messenger.media

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceRecorderTest {

    /** iOS `normalizedWaveform`: 100 averaged buckets; a short note keeps its samples as they are. */
    @Test
    fun `the waveform is 100 averages, or fewer for a short note`() {
        val short = List(40) { it / 40f }
        assertEquals(short, VoiceRecorder.waveform(short))

        val long = List(400) { if (it % 2 == 0) 0f else 1f }
        val w = VoiceRecorder.waveform(long)
        assertEquals(100, w.size)
        w.forEach { assertEquals(0.5f, it, 1e-6f) }
    }
}
