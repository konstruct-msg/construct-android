package com.construct.messenger.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import com.construct.messenger.util.BlurHash
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A picked photo made ready to send. **Canon:** iOS `MediaOptimizer.optimizeImage` (compressed
 * path): the original resolution when the long side is at most 1920, otherwise 1920, then 1440,
 * 1080, 1024 until it fits; at each size the highest JPEG quality between 0.35 and 0.88 that is
 * within 4 MiB. A 4×3 BlurHash of the picture at 32 px goes with it.
 *
 * The picture is decoded and drawn again, so nothing of the file's metadata — EXIF, GPS — is
 * carried; the orientation it recorded is applied first.
 */
@Singleton
class ImagePreparer @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    class Prepared(val jpeg: ByteArray, val width: Int, val height: Int, val blurhash: String?)

    fun prepare(uri: Uri): Prepared {
        val source = decode(uri, MAX_SIDES.first())
        val opaque = if (source.hasAlpha()) flatten(source) else source
        for (side in MAX_SIDES) {
            val bitmap = scaledTo(opaque, side)
            val jpeg = bestJpeg(bitmap)
            if (jpeg != null) return Prepared(jpeg, bitmap.width, bitmap.height, blurhash(bitmap))
        }
        error("photo does not fit ${MAX_BYTES} bytes at any size")
    }

    /** Decoded upright, its long side no longer than [maxSide]. */
    private fun decode(uri: Uri, maxSide: Int): Bitmap {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder applies the EXIF orientation itself.
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val long = max(info.size.width, info.size.height)
                if (long > maxSide) {
                    val f = maxSide.toFloat() / long
                    decoder.setTargetSize((info.size.width * f).roundToInt(), (info.size.height * f).roundToInt())
                }
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val bitmap = context.contentResolver.openInputStream(uri)!!.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("not an image")
        val degrees = context.contentResolver.openInputStream(uri)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        return if (degrees == 0f) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees) }, true)
    }

    /** JPEG has no transparency; iOS sends such a picture as PNG, here it goes onto white. */
    private fun flatten(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).apply { drawColor(Color.WHITE); drawBitmap(src, 0f, 0f, null) }
        return out
    }

    private fun scaledTo(src: Bitmap, side: Int): Bitmap {
        val long = max(src.width, src.height)
        if (long <= side) return src
        val f = side.toFloat() / long
        return Bitmap.createScaledBitmap(src, (src.width * f).roundToInt(), (src.height * f).roundToInt(), true)
    }

    /** The highest quality in [Q_MIN]..[Q_MAX] (in whole percent) within [MAX_BYTES], or null. */
    private fun bestJpeg(bitmap: Bitmap): ByteArray? {
        fun at(q: Int) = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, q, it) }.toByteArray()
        at(Q_MAX).let { if (it.size <= MAX_BYTES) return it }
        var best = at(Q_MIN).takeIf { it.size <= MAX_BYTES } ?: return null
        var lo = Q_MIN + 1
        var hi = Q_MAX - 1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            val bytes = at(mid)
            if (bytes.size <= MAX_BYTES) { best = bytes; lo = mid + 1 } else hi = mid - 1
        }
        return best
    }

    private fun blurhash(bitmap: Bitmap): String? = runCatching {
        val f = BLUR_SIDE.toFloat() / max(bitmap.width, bitmap.height)
        val w = max(1, (bitmap.width * f).roundToInt())
        val h = max(1, (bitmap.height * f).roundToInt())
        val small = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val px = IntArray(w * h).also { small.getPixels(it, 0, w, 0, 0, w, h) }
        BlurHash.encode(px, w, h)
    }.getOrNull()

    companion object {
        val MAX_SIDES = listOf(1920, 1440, 1080, 1024)
        const val MAX_BYTES = 4 * 1024 * 1024
        const val Q_MIN = 35
        const val Q_MAX = 88
        private const val BLUR_SIDE = 32
    }
}
