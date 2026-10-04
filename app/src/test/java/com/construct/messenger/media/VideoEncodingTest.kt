package com.construct.messenger.media

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Test

/** construct-docs TODO 112: video goes out as HEVC, half H.264's bitrate. */
class VideoEncodingTest {

    /** Mutation: ask for HEVC regardless — Media3 shrinks the picture to what the encoder takes. */
    @Test
    fun `HEVC only where an encoder takes the frame`() {
        assertEquals(MimeTypes.VIDEO_H265, VideoEncoding.mimeFor(hevcFits = true))
        assertEquals(MimeTypes.VIDEO_H264, VideoEncoding.mimeFor(hevcFits = false))
    }

    @Test
    fun `a frame is fitted into the box the way it stands`() {
        assertEquals(1920 to 1080, VideoEncoding.fit(3840, 2160, 1920, 1080))
        assertEquals(1080 to 1920, VideoEncoding.fit(2160, 3840, 1080, 1920))
        assertEquals(1440 to 1080, VideoEncoding.fit(4032, 3024, 1920, 1080))
        assertEquals(1280 to 720, VideoEncoding.fit(1280, 720, 1920, 1080))
    }

    /** Media3's own figure for H.264 — what a device without an HEVC encoder still sends. */
    @Test
    fun `H264 keeps the Kush gauge`() {
        assertEquals(8_709_120, VideoEncoding.bitrate(1920, 1080, 30f, MimeTypes.VIDEO_H264))
    }

    /** Mutation: give HEVC H.264's bitrate — the file is no smaller and the change buys nothing. */
    @Test
    fun `HEVC gets half`() {
        assertEquals(4_354_560, VideoEncoding.bitrate(1920, 1080, 30f, MimeTypes.VIDEO_H265))
        assertEquals(
            VideoEncoding.bitrate(1280, 720, 60f, MimeTypes.VIDEO_H264) / 2,
            VideoEncoding.bitrate(1280, 720, 60f, MimeTypes.VIDEO_H265),
        )
    }
}
