package com.construct.messenger.media

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Voice notes' speed and continuous playback, as iOS `AudioPlayerService` keeps them (TODO 149). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class VoicePlaybackSettingsTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `1x and not continuous until chosen, then that, after a restart too`() {
        VoicePlaybackSettings(context).let {
            assertEquals(1f, it.rate.value)
            assertFalse(it.continuous.value)
            it.setRate(1.5f)
            it.setContinuous(true)
        }
        VoicePlaybackSettings(context).let {
            assertEquals(1.5f, it.rate.value)
            assertTrue(it.continuous.value)
        }
    }

    @Test
    fun `the speed cycles 1, 1_25, 1_5, 2 and back, an unknown one starts it again`() {
        assertEquals(listOf(1.25f, 1.5f, 2f, 1f), listOf(1f, 1.25f, 1.5f, 2f).map(VoicePlaybackSettings::nextRate))
        assertEquals(1f, VoicePlaybackSettings.nextRate(3f))
    }

    @Test
    fun `a speed nobody offers is kept as 1x`() {
        VoicePlaybackSettings(context).setRate(3f)
        assertEquals(1f, VoicePlaybackSettings(context).rate.value)
    }

    @Test
    fun `labels read as on iOS`() {
        assertEquals(listOf("1×", "1.25×", "1.5×", "2×"), VoicePlaybackSettings.RATES.map(VoicePlaybackSettings::rateLabel))
    }
}
