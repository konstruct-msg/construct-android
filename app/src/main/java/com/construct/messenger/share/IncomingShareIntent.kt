package com.construct.messenger.share

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import com.construct.messenger.data.model.ComposerCapacity

/** The exported Activity's input adapter. No I/O, recipient selection, or sending. */
object IncomingShareIntent {
    data class Payload(val text: String, val uris: List<Uri>)

    sealed interface Result {
        data object NotShare : Result
        data class Content(val payload: Payload) : Result
        data object Invalid : Result
    }

    fun read(intent: Intent?): Result {
        val action = intent?.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return Result.NotShare
        return try {
            val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
            // EXTRA_STREAM is authoritative; ClipData is a fallback, not a second list to append.
            val uris = if (intent.hasExtra(Intent.EXTRA_STREAM)) {
                if (action == Intent.ACTION_SEND_MULTIPLE) {
                    IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                        ?: return Result.Invalid
                } else {
                    listOf(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                        ?: return Result.Invalid)
                }
            } else {
                val clip = intent.clipData
                if (clip == null) emptyList() else (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
            }.distinct()
            if (uris.size > ComposerCapacity.ATTACHMENTS || uris.any { !isExternalContent(it) }) return Result.Invalid
            if (text.isBlank() && uris.isEmpty()) return Result.Invalid
            Result.Content(Payload(text, uris))
        } catch (_: RuntimeException) {
            // Includes BadParcelableException and wrong-type extras. Never log the Intent.
            Result.Invalid
        }
    }

    fun isExternalContent(uri: Uri): Boolean = uri.scheme == "content" &&
        !uri.authority.isNullOrBlank() && '@' !in uri.authority.orEmpty()
}

/** Private, process-local source URIs. Keys and provider input never appear in them. */
object ShareSourceUris {
    fun authority(packageName: String) = "$packageName.incoming-share"
    fun source(packageName: String, request: String, item: String): Uri = Uri.Builder()
        .scheme("content").authority(authority(packageName)).appendPath(request).appendPath(item).build()

    fun ids(uri: Uri, packageName: String): Pair<String, String>? {
        if (uri.scheme != "content" || uri.authority != authority(packageName)) return null
        val parts = uri.pathSegments
        if (parts.size != 2 || parts.any { !isId(it) }) return null
        return parts[0] to parts[1]
    }

    fun isId(value: String): Boolean = runCatching { java.util.UUID.fromString(value).toString() == value }.getOrDefault(false)
}
