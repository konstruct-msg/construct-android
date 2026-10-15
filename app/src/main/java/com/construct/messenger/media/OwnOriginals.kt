package com.construct.messenger.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.construct.messenger.diagnostics.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Files this device sent, read from where they already are rather than kept twice (construct-docs
 * TODO 154). A file sent as a file — a track, a document — goes as its own bytes, so the original
 * the user picked is the same file the message names. Once it is uploaded, the app keeps a link to
 * it (a persistable grant on the document) instead of its own sealed copy, and plays or opens it
 * from there.
 *
 * A link can break: the file moved, deleted, or changed after it was sent. Each is remembered with
 * its size and SHA-256, and anything that does not match is not used — the message then fetches
 * the sent copy from the store, as for anyone else, while the store still has it.
 *
 * What it keeps, on this device only: which local file each sent media id came from. The message
 * row already names the file. A local wipe clears it with the grants (`LocalDataWipe`).
 */
@Singleton
class OwnOriginals @Inject constructor(@ApplicationContext private val context: Context) {
    private val prefs = context.getSharedPreferences("own_originals", Context.MODE_PRIVATE)

    /**
     * Remember [uri] as the original of [mediaId], whose plaintext was [size] bytes hashing to
     * [sha256]. False when the picker gave no lasting grant: the app's copy must then stay.
     */
    fun remember(mediaId: String, uri: Uri, size: Long, sha256: ByteArray): Boolean {
        val granted = runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.isSuccess
        if (!granted) return false
        prefs.edit().putString(mediaId, encode(Entry(uri.toString(), size, sha256))).commit()
        return true
    }

    /** The original of [mediaId] as it was sent, or null: none kept, or it no longer is that file. */
    fun read(mediaId: String): ByteArray? {
        val entry = prefs.getString(mediaId, null)?.let(::decode) ?: return null
        val bytes = try {
            context.contentResolver.openInputStream(Uri.parse(entry.uri))?.use { it.readBytes() }
        } catch (e: Exception) {
            null
        }
        if (bytes == null || bytes.size.toLong() != entry.size || !sha256(bytes).contentEquals(entry.sha256)) {
            Log.i(TAG, "original of ${mediaId.take(8)}… is gone or changed; the store's copy is used")
            return null
        }
        return bytes
    }

    internal data class Entry(val uri: String, val size: Long, val sha256: ByteArray)

    internal companion object {
        const val TAG = "OwnOriginals"

        fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

        /** `size:hex(sha256):uri` — the uri last, as it may hold anything. */
        fun encode(e: Entry): String = "${e.size}:${e.sha256.joinToString("") { "%02x".format(it) }}:${e.uri}"

        fun decode(s: String): Entry? {
            val parts = s.split(':', limit = 3)
            if (parts.size != 3) return null
            val size = parts[0].toLongOrNull() ?: return null
            val hex = parts[1]
            if (hex.length != 64) return null
            val sha = runCatching { ByteArray(32) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() } }.getOrNull() ?: return null
            return Entry(parts[2], size, sha)
        }
    }
}
