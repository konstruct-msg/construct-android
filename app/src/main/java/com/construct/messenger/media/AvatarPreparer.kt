package com.construct.messenger.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import java.io.ByteArrayOutputStream
import kotlin.math.min

/**
 * An avatar as it is kept and sent. **Canon:** iOS `ImageHelper.prepareAvatarImage` — the picture
 * centre-cropped to a 512 px square on white, JPEG at 0.8, stepped down by 0.1 until it is within
 * 200 KiB. Drawn again, so nothing of the source file's metadata survives.
 */
object AvatarPreparer {
    const val SIDE = 512
    const val MAX_BYTES = 200 * 1024

    /** A received avatar is bounded too: what iOS sends is far below either. */
    const val MAX_RECEIVED_BYTES = 1024 * 1024
    private const val MAX_RECEIVED_SIDE = 4096

    fun encode(picture: Bitmap): ByteArray? {
        val square = min(picture.width, picture.height)
        if (square <= 0) return null
        val out = Bitmap.createBitmap(SIDE, SIDE, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.WHITE)
            val left = (picture.width - square) / 2
            val top = (picture.height - square) / 2
            drawBitmap(picture, Rect(left, top, left + square, top + square), Rect(0, 0, SIDE, SIDE), null)
        }
        var quality = 80
        while (true) {
            val jpeg = ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
            if (jpeg.size <= MAX_BYTES) return jpeg
            if (quality <= 10) return null
            quality -= 10
        }
    }

    /** Whether [bytes], from a peer, is a picture of a sane size — checked before it is stored. */
    fun isAcceptable(bytes: ByteArray): Boolean {
        if (bytes.isEmpty() || bytes.size > MAX_RECEIVED_BYTES) return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return bounds.outWidth in 1..MAX_RECEIVED_SIDE && bounds.outHeight in 1..MAX_RECEIVED_SIDE
    }
}
