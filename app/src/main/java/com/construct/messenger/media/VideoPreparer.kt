package com.construct.messenger.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.Mp4OrientationData
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultMuxer
import androidx.media3.transformer.EditedMediaItem
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
        val dir = File(context.cacheDir, "video").apply { mkdirs() }
        val out = File(dir, "v_${UUID.randomUUID()}.mp4")
        try {
            val (w, h) = displaySize(uri)
            transcode(uri, out, w, h)
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
    private suspend fun transcode(uri: Uri, out: File, width: Int, height: Int) = withContext(Dispatchers.Main) {
        // Fit within 1920×1080 the way the picture stands; smaller videos keep their size.
        val landscape = width >= height
        val (boxW, boxH) = if (landscape) LONG to SHORT else SHORT to LONG
        val effects = if (width > boxW || height > boxH) {
            Effects(emptyList(), listOf(Presentation.createForWidthAndHeight(boxW, boxH, Presentation.LAYOUT_SCALE_TO_FIT)))
        } else {
            Effects.EMPTY
        }
        val item = EditedMediaItem.Builder(androidx.media3.common.MediaItem.fromUri(uri)).setEffects(effects).build()
        suspendCancellableCoroutine { cont ->
            val transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
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
            transformer.start(item, out.absolutePath)
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
    }
}
