package com.construct.messenger.media

import java.util.zip.Inflater

/** What a received file is, once opened. */
object FileContent {

    /**
     * iOS `MediaManager.compressIfBeneficial` may send a document raw-DEFLATEd (Apple's `.zlib`)
     * when that saves 10 %, and nothing on the wire says so — iOS's own receiver shows those
     * bytes as they are. Here a file whose type is not already compressed is tried as raw
     * DEFLATE and taken inflated only when the stream decodes exactly to its end; ordinary text
     * or data fails that within a few bytes. Android never sends compressed files.
     */
    fun unpacked(bytes: ByteArray, mime: String): ByteArray {
        if (mime.lowercase() in ALREADY_COMPRESSED || bytes.size <= 2) return bytes
        return inflateExactly(bytes) ?: bytes
    }

    internal fun inflateExactly(bytes: ByteArray): ByteArray? = runCatching {
        val inflater = Inflater(true)
        try {
            inflater.setInput(bytes)
            val out = java.io.ByteArrayOutputStream(bytes.size * 3)
            val buf = ByteArray(64 * 1024)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) return@runCatching null
                out.write(buf, 0, n)
                if (out.size() > MAX_INFLATED) return@runCatching null
            }
            if (inflater.remaining != 0) null else out.toByteArray()
        } finally {
            inflater.end()
        }
    }.getOrNull()

    /** A peer-chosen name as a file name: no path, nothing hidden, not empty. */
    fun safeName(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\').trim().trimStart('.')
        return base.take(120).ifEmpty { "file" }
    }

    /** iOS `alreadyCompressedMimeTypes`. */
    private val ALREADY_COMPRESSED = setOf(
        "image/jpeg", "image/png", "image/gif", "image/webp", "image/heic",
        "video/mp4", "video/quicktime", "video/mpeg", "video/x-msvideo",
        "audio/mpeg", "audio/aac", "audio/mp4", "audio/ogg",
        "application/pdf",
        "application/zip", "application/gzip", "application/x-bzip2",
        "application/x-rar-compressed", "application/x-7z-compressed",
    )

    private const val MAX_INFLATED = 200L * 1024 * 1024
}
