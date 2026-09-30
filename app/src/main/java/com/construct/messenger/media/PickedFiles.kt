package com.construct.messenger.media

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** A file the user picked, read whole: what it is called, what it is, and its bytes. */
@Singleton
class PickedFiles @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    class Picked(val name: String, val mime: String, val bytes: ByteArray)

    class TooLarge(val bytes: Long) : Exception("file of $bytes bytes is over the store's limit")

    /** Name and size, without reading it. */
    fun describe(uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment ?: "file"
        var size = -1L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        return FileContent.safeName(name) to size
    }

    fun read(uri: Uri): Picked {
        val (name, size) = describe(uri)
        if (size > MAX_BYTES) throw TooLarge(size)
        val bytes = context.contentResolver.openInputStream(uri)!!.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > MAX_BYTES) throw TooLarge(out.size().toLong())
            }
            out.toByteArray()
        }
        val mime = context.contentResolver.getType(uri)
            ?: android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"
        return Picked(name, mime, bytes)
    }

    companion object {
        /** The store takes 100 MiB of ciphertext; the seal adds 28 bytes. */
        const val MAX_BYTES = 100L * 1024 * 1024 - MediaCrypto.OVERHEAD
    }
}
