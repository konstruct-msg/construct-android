package com.construct.messenger.media

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The quality last chosen is the next video's, as iOS `composer.videoQuality` (TODO 148). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class VideoQualityStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `1080p until something else is chosen, then that, after a restart too`() {
        assertEquals(VideoQuality.P1080, VideoQualityStore(context).state.value)
        VideoQualityStore(context).save(VideoQuality.ORIGINAL)
        assertEquals(VideoQuality.ORIGINAL, VideoQualityStore(context).state.value)
    }

    @Test
    fun `the boxes are iOS's`() {
        assertEquals(1280 to 720, VideoQuality.P720.long to VideoQuality.P720.short)
        assertEquals(1920 to 1080, VideoQuality.P1080.long to VideoQuality.P1080.short)
    }
}
