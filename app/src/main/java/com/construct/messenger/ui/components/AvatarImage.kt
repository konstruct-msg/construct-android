package com.construct.messenger.ui.components

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * [jpeg], an avatar as stored, decoded for [CTAvatar]; `null` for none or for bytes that are not
 * a picture. Rows arrive as new arrays on every list update, so the decoded picture is kept by
 * content: a chat list that changes on each message does not decode each avatar again.
 */
@Composable
fun rememberAvatar(jpeg: ByteArray?): ImageBitmap? {
    val key = jpeg?.let { it.size.toLong() shl 32 or (it.contentHashCode().toLong() and 0xFFFFFFFFL) }
    return remember(key) {
        if (jpeg == null || key == null) return@remember null
        decoded.get(key) ?: BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, HALF)?.asImageBitmap()?.also { decoded.put(key, it) }
    }
}

/** 256 px: the largest avatar drawn, 96 dp, at the densest screens. A quarter of the memory. */
private val HALF = BitmapFactory.Options().apply { inSampleSize = 2 }

/** 256 KiB each decoded; a few screenfuls. */
private val decoded = LruCache<Long, ImageBitmap>(48)
