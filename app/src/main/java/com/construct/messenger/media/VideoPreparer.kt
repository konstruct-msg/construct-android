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

    suspend fun prepare(uri: Uri): Prepared = prepare(uri, note = false)

    /**
     * A recorded video note: the centre 3:4 of the upright frame — what the viewfinder showed —
     * at 720×960. **Canon:** iOS `MediaManager.videoNoteRender`.
     */
    suspend fun prepareNote(uri: Uri): Prepared = prepare(uri, note = true)

    private suspend fun prepare(uri: Uri, note: Boolean): Prepared {
        val dir = File(context.cacheDir, "video").apply { mkdirs() }
        val out = File(dir, "v_${UUID.randomUUID()}.mp4")
        try {
            val (w, h) = displaySize(uri)
            transcode(uri, out, w, h, note)
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
    private suspend fun transcode(uri: Uri, out: File, width: Int, height: Int, note: Boolean) = withContext(Dispatchers.Main) {
        // Fit within 1920×1080 the way the picture stands; smaller videos keep their size. A note
        // is cropped to its centre 3:4 and filled into 720×960 (effects see the upright frame).
        val landscape = width >= height
        val (boxW, boxH) = if (landscape) LONG to SHORT else SHORT to LONG
        val effects = when {
            note -> Effects(emptyList(), listOf(Presentation.createForWidthAndHeight(NOTE_W, NOTE_H, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)))
            width > boxW || height > boxH ->
                Effects(emptyList(), listOf(Presentation.createForWidthAndHeight(boxW, boxH, Presentation.LAYOUT_SCALE_TO_FIT)))
            else -> Effects.EMPTY
        }
        val item = EditedMediaItem.Builder(androidx.media3.common.MediaItem.fromUri(uri)).setEffects(effects).build()
        val (outW, outH) = if (note) NOTE_W to NOTE_H else VideoEncoding.fit(width, height, boxW, boxH)
        val mime = VideoEncoding.mimeFor(hevcFits = VideoEncoding.hevcEncodes(outW, outH))
        val composition = Composition.Builder(EditedMediaItemSequence.Builder(item).build())
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
                runCatching { info.getCapabilitiesForType(MimeTypes.VIDEO_H265).videoCapabilities.isSizeSupported(width, height) }
                    .getOrDefault(false)
        }

    fun bitrate(width: Int, height: Int, fps: Float, mime: String): Int {
        val kush = width.toDouble() * height * fps * 0.07 * 2
        return (if (mime == MimeTypes.VIDEO_H265) kush * HEVC_SHARE else kush).toInt()
    }
}

