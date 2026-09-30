package com.construct.messenger.service

import android.content.Context
import com.construct.messenger.R
import com.construct.messenger.data.model.MessageMedia
import com.construct.messenger.util.MediaWire
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * The chat list's line for a message with media. **Canon:** iOS `Chat.lastMessagePreview` — the
 * caption if there is one, else "Photo" for any album of pictures or videos, the file's name (or
 * "N files") for files, "Voice message" for a voice note. Stored with the chat, as the text is.
 */
fun interface MediaPreviewText {
    fun of(media: MediaWire.Stored): String
}

class AndroidMediaPreviewText @Inject constructor(
    @ApplicationContext private val context: Context,
) : MediaPreviewText {
    override fun of(media: MediaWire.Stored): String {
        if (media.caption.isNotBlank()) return media.caption
        return when (val decoded = MediaWire.decode(media.kind, media.bytes)) {
            is MessageMedia.Voice -> context.getString(R.string.voice_message)
            is MessageMedia.Album -> when {
                !decoded.isFiles -> context.getString(R.string.photo)
                decoded.items.size == 1 -> decoded.items[0].filename ?: context.getString(R.string.file_attachment)
                else -> context.resources.getQuantityString(R.plurals.n_files, decoded.items.size, decoded.items.size)
            }
            null -> context.getString(R.string.photo)
        }
    }
}
