package com.construct.messenger.data.model

/**
 * A reply reference carried inside the encrypted text.
 *
 * **Canon:** iOS `QuotedMessage` + `ReplyPreviewPayload` (text kind). The id is the
 * quoted message's id, lowercased the way iOS stores `replyToMessageId`. [preview]
 * is user-authored text only, at most [MAX_CHARS] — the proto's own cap and iOS
 * `maxWireTextCharacters`. [mediaType] is the proto `MediaType` name when the quote
 * is a photo, voice note, or file and the preview text is a caption (or empty).
 * Nothing here is written on the envelope: the server already sees who talks to
 * whom, and a reply target would tell it which message.
 */
data class ReplyRef(
    val messageId: String,
    val preview: String,
    val mediaType: String? = null,
) {
    companion object {
        const val MAX_CHARS = 200

        fun of(messageId: String, text: String, mediaType: String? = null): ReplyRef? {
            val id = messageId.trim().lowercase()
            if (id.isEmpty()) return null
            return ReplyRef(
                messageId = id,
                preview = text.trim().take(MAX_CHARS),
                mediaType = mediaType,
            )
        }
    }
}
