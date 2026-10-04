package com.construct.messenger.media

import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A video goes out as HEVC where the device can encode it at full size, and never smaller than it
 * came in (construct-docs TODO 112). Emulator only:
 * run it with `adb -s emulator-5554 shell am instrument`, never `connectedAndroidTest` with a phone
 * attached.
 */
@RunWith(AndroidJUnit4::class)
class VideoPreparerInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @OptIn(UnstableApi::class)
    private fun h264Source(): File {
        val png = File(context.cacheDir, "gradient.png")
        val bmp = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        // A smooth gradient: a poster from it fits the in-message thumbnail budget (3258 bytes),
        // which hard-edged noise does not at any size.
        bmp.setPixels(IntArray(1920 * 1080) { i -> (0xFF shl 24) or ((i % 1920) * 255 / 1920 shl 16) or ((i / 1920) * 255 / 1080 shl 8) or 0x80 }, 0, 1920, 0, 0, 1920, 1080)
        png.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val out = File(context.cacheDir, "source.mp4").apply { delete() }
        val item = EditedMediaItem.Builder(MediaItem.Builder().setUri(Uri.fromFile(png)).setImageDurationMs(3_000).build())
            .setFrameRate(30)
            .build()
        val done = CountDownLatch(1)
        var error: Exception? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) = done.countDown()
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        error = exportException
                        done.countDown()
                    }
                })
                .build()
                .start(item, out.absolutePath)
        }
        assertTrue("source not made in time", done.await(120, TimeUnit.SECONDS))
        error?.let { throw it }
        return out
    }

    private fun videoMime(bytes: ByteArray): String {
        val f = File(context.cacheDir, "prepared.mp4").apply { writeBytes(bytes) }
        val ex = MediaExtractor()
        try {
            ex.setDataSource(f.absolutePath)
            return (0 until ex.trackCount).map { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty() }
                .first { it.startsWith("video/") }
        } finally {
            ex.release()
            f.delete()
        }
    }

    @Test
    fun aVideoIsSentAsHevc(): Unit = runBlocking {
        val source = h264Source()
        val prepared = VideoPreparer(context, ImagePreparer(context)).prepare(Uri.fromFile(source))
        Log.i("VideoPreparerTest", "source H.264 ${source.length()} bytes → prepared ${prepared.mp4.size} bytes, ${prepared.durationMs} ms")
        // HEVC where an encoder here takes 1080p; the emulator's does not, so it gets H.264.
        val expected = if (VideoEncoding.hevcEncodes(1920, 1080)) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264
        assertEquals(expected, videoMime(prepared.mp4))
        assertEquals(1920, prepared.width)
        assertEquals(1080, prepared.height)
        assertTrue(prepared.thumbnail != null)
        source.delete()
    }

    /** A note is the centre 3:4 of the frame at 720×960, whatever the source's shape. */
    @Test
    fun aNoteIsTheCentreThreeByFourAt720x960(): Unit = runBlocking {
        val source = h264Source()
        val prepared = VideoPreparer(context, ImagePreparer(context)).prepareNote(Uri.fromFile(source))
        assertEquals(720, prepared.width)
        assertEquals(960, prepared.height)
        source.delete()
    }
}

