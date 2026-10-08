package com.construct.messenger.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.Mp4OrientationData
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Codec
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.DefaultMuxer
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Muxer
import androidx.media3.transformer.Transformer
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * A picked video made ready to send. **Canon:** iOS `MediaManager.uploadVideo` — re-encoded to
 * MP4 at 1080p (its default preset; the picker's 720p/original choices are not here), a poster
 * from the first frame sent as the thumbnail, a BlurHash, pixel size and length.
 *
 * HEVC at half H.264's bitrate where an HEVC encoder on the device takes the frame size, H.264
 * otherwise; HDR is tone-mapped to SDR, as iOS does for 720p and 1080p ([VideoEncoding],
 * construct-docs TODO 112).
 *
 * Re-encoding also drops what the file says about itself: the muxer is handed only the frame
 * orientation, never the recording's location or dates, which the source may carry.
 */
@Singleton
class VideoPreparer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val images: ImagePreparer,
) {
    class Prepared(
        val mp4: ByteArray,
        val width: Int,
        val height: Int,
        val durationMs: Long,
        val thumbnail: ByteArray?,
        val blurhash: String?,
    )

    class TooLarge : Exception("video over the store's limit after re-encoding")

    fun isVideo(uri: Uri): Boolean = context.contentResolver.getType(uri)?.startsWith("video/") == true

    suspend fun prepare(uri: Uri): Prepared {
        val (w, h) = displaySize(uri)
        val landscape = w >= h
        val (boxW, boxH) = if (landscape) LONG to SHORT else SHORT to LONG
        // Fit within 1920×1080 the way the picture stands; smaller videos keep their size.
        val fit = if (w > boxW || h > boxH) listOf(Presentation.createForWidthAndHeight(boxW, boxH, Presentation.LAYOUT_SCALE_TO_FIT)) else emptyList()
        val item = EditedMediaItem.Builder(androidx.media3.common.MediaItem.fromUri(uri))
            .setEffects(Effects(emptyList(), frameCap(uri) + fit))
            .build()
        val (outW, outH) = VideoEncoding.fit(w, h, boxW, boxH)
        return encode(listOf(item), outW, outH)
    }

    /**
     * A recorded video note: its segments joined, the kept stretch of them (cut to the frame — the
     * trim rides into this one encode, iOS `transcodeVideo(timeRange:)`), the centre 3:4 of the
     * upright frame — what the viewfinder showed — at 720×960. **Canon:** iOS `videoNoteRender`.
     */
    suspend fun prepareNote(take: VideoNoteTake): Prepared {
        // Effects see the upright frame: the crop is the centre of what stood in the viewfinder.
        val crop = Presentation.createForWidthAndHeight(NOTE_W, NOTE_H, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)
        val items = VideoNoteTrim.clips(take.segments.map { it.durationMs }, take.trimMs).map { (i, range) ->
            val uri = Uri.fromFile(take.segments[i].file)
            val media = androidx.media3.common.MediaItem.Builder()
                .setUri(uri)
                .setClippingConfiguration(
                    androidx.media3.common.MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(range.first)
                        .setEndPositionMs(range.last)
                        .build(),
                )
                .build()
            EditedMediaItem.Builder(media).setEffects(Effects(emptyList(), frameCap(uri) + crop)).build()
        }
        require(items.isNotEmpty()) { "nothing kept" }
        return encode(items, NOTE_W, NOTE_H)
    }

    /** iOS `maxSentFrameRate`: at most 30 frames a second. Only a faster source is touched — at
     * 30 the drop effect could lose frames to timestamp jitter. */
    @OptIn(UnstableApi::class)
    private fun frameCap(uri: Uri): List<androidx.media3.common.Effect> =
        if (VideoEncoding.needsFrameCap(sourceFps(uri))) {
            listOf(androidx.media3.effect.FrameDropEffect.createDefaultFrameDropEffect(VideoEncoding.MAX_FPS))
        } else {
            emptyList()
        }

    private fun sourceFps(uri: Uri): Float? {
        val ex = android.media.MediaExtractor()
        return try {
            ex.setDataSource(context, uri, null)
            (0 until ex.trackCount).map(ex::getTrackFormat)
                .firstOrNull { it.getString(android.media.MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                ?.takeIf { it.containsKey(android.media.MediaFormat.KEY_FRAME_RATE) }
                ?.let { runCatching { it.getInteger(android.media.MediaFormat.KEY_FRAME_RATE).toFloat() }.getOrElse { _ -> it.getFloat(android.media.MediaFormat.KEY_FRAME_RATE) } }
        } catch (e: Exception) {
            null
        } finally {
            ex.release()
        }
    }

    private suspend fun encode(items: List<EditedMediaItem>, outW: Int, outH: Int): Prepared {
        val dir = File(context.cacheDir, "video").apply { mkdirs() }
        val out = File(dir, "v_${UUID.randomUUID()}.mp4")
        try {
            transcode(items, out, outW, outH)
            if (out.length() > PickedFiles.MAX_BYTES) throw TooLarge()
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(out.absolutePath)
                val (ow, oh) = displaySize(retriever)
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val poster = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                return Prepared(
                    mp4 = out.readBytes(),
                    width = ow,
                    height = oh,
                    durationMs = duration,
                    thumbnail = poster?.let(images::thumbnail),
                    blurhash = poster?.let(images::blurhash),
                )
            } finally {
                retriever.release()
            }
        } finally {
            out.delete()
        }
    }

    private fun displaySize(uri: Uri): Pair<Int, Int> {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            return displaySize(r)
        } finally {
            r.release()
        }
    }

    private fun displaySize(r: MediaMetadataRetriever): Pair<Int, Int> {
        val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        val rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        return if (rotation % 180 == 0) w to h else h to w
    }

    @OptIn(UnstableApi::class)
    private suspend fun transcode(items: List<EditedMediaItem>, out: File, outW: Int, outH: Int) = withContext(Dispatchers.Main) {
        val mime = VideoEncoding.mimeFor(hevcFits = VideoEncoding.hevcEncodes(outW, outH))
        val composition = Composition.Builder(EditedMediaItemSequence.Builder(items).build())
            .apply {
                // KEEP_HDR (the default) would send 10-bit HDR where the device can encode it.
                if (android.os.Build.VERSION.SDK_INT >= 29) setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            }
            .build()
        suspendCancellableCoroutine { cont ->
            val transformer = Transformer.Builder(context)
                .setVideoMimeType(mime)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(Bitrated(DefaultEncoderFactory.Builder(context).build()))
                .setMuxerFactory(OrientationOnly(DefaultMuxer.Factory()))
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        cont.resume(Unit)
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        cont.resumeWithException(exportException)
                    }
                })
                .build()
            cont.invokeOnCancellation { transformer.cancel() }
            transformer.start(composition, out.absolutePath)
        }
    }

    /** Gives the encoder [VideoEncoding.bitrate] for the codec Media3 settled on. */
    @OptIn(UnstableApi::class)
    private class Bitrated(private val inner: Codec.EncoderFactory) : Codec.EncoderFactory by inner {
        override fun createForVideoEncoding(format: Format): Codec {
            val mime = format.sampleMimeType
            if (mime == null || format.averageBitrate != Format.NO_VALUE || format.width <= 0 || format.height <= 0) {
                return inner.createForVideoEncoding(format)
            }
            val fps = if (format.frameRate > 0) format.frameRate else VideoEncoding.DEFAULT_FPS
            val bitrate = VideoEncoding.bitrate(format.width, format.height, fps, mime)
            return inner.createForVideoEncoding(format.buildUpon().setAverageBitrate(bitrate).build())
        }
    }

    /** The muxer the output goes through, told of nothing but how the frames stand. */
    @OptIn(UnstableApi::class)
    private class OrientationOnly(private val inner: Muxer.Factory) : Muxer.Factory by inner {
        override fun create(path: String): Muxer {
            val muxer = inner.create(path)
            return object : Muxer by muxer {
                override fun addMetadataEntry(metadataEntry: Metadata.Entry) {
                    if (metadataEntry is Mp4OrientationData) muxer.addMetadataEntry(metadataEntry)
                }
            }
        }
    }

    private companion object {
        const val LONG = 1920
        const val SHORT = 1080
        const val NOTE_W = 720
        const val NOTE_H = 960
    }
}

/**
 * Which codec a sent video is encoded with, and at what bitrate. Pure, so the rule is testable.
 * H.264 keeps Media3's own figure (the Kush gauge, medium motion: 1080p30 ≈ 8.7 Mbit/s); HEVC gets
 * half of it — the same picture at about half the size is the reason to send HEVC at all.
 */
object VideoEncoding {
    const val DEFAULT_FPS = 30f

    /** iOS `maxSentFrameRate`. */
    const val MAX_FPS = 30f

    /** Whether a source at [fps] is thinned to [MAX_FPS]; unknown is left alone. */
    fun needsFrameCap(fps: Float?): Boolean = fps != null && fps > MAX_FPS + 1
    const val HEVC_SHARE = 0.5

    /**
     * HEVC only where an encoder takes the whole frame. Asked for HEVC regardless, Media3 settles
     * for the size the encoder takes: the emulator's software encoder turned 1080p into 480×270
     * (2026-10-04). A smaller picture is a worse trade than a larger file.
     */
    fun mimeFor(hevcFits: Boolean): String = if (hevcFits) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264

    /** [width]×[height] scaled to fit [boxW]×[boxH], even sides; a smaller frame keeps its size. */
    fun fit(width: Int, height: Int, boxW: Int, boxH: Int): Pair<Int, Int> {
        if (width <= boxW && height <= boxH) return width to height
        val scale = minOf(boxW.toDouble() / width, boxH.toDouble() / height)
        fun even(v: Double) = (Math.round(v / 2) * 2).toInt()
        return even(width * scale) to even(height * scale)
    }

    /** Whether an HEVC encoder on this device takes a [width]×[height] frame. */
    fun hevcEncodes(width: Int, height: Int): Boolean =
        android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder && MimeTypes.VIDEO_H265 in info.supportedTypes.map { it.lowercase() } &&
                runCatching { info.getCapabilitiesForType(MimeTypes.VIDEO_H265).videoCapabilities?.isSizeSupported(width, height) == true }
                    .getOrDefault(false)
        }

    fun bitrate(width: Int, height: Int, fps: Float, mime: String): Int {
        val kush = width.toDouble() * height * fps * 0.07 * 2
        return (if (mime == MimeTypes.VIDEO_H265) kush * HEVC_SHARE else kush).toInt()
    }
}

