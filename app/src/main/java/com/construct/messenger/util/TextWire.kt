package com.construct.messenger.util

import com.construct.messenger.data.model.ReplyRef
import shared.proto.messaging.v1.Content.MediaType
import shared.proto.messaging.v1.Content.MessageContent
import shared.proto.messaging.v1.Content.QuotedMessage
import shared.proto.messaging.v1.Content.TextMessage

/**
 * 1:1 text `MessageContent` — the bytes that go inside a KNST frame.
 *
 * A reply is a [QuotedMessage] on the text, the same fields iOS `buildQuoted` sets:
 * message id and a short preview, plus a media type when the quoted message is not
 * text. `sender_id` stays unset. iOS does not fill it, and the id alone is what
 * both clients look up.
 */
object TextWire {
    fun encode(text: String, reply: ReplyRef? = null): ByteArray {
        val textMsg = TextMessage.newBuilder().setText(text)
        quoted(reply)?.let { textMsg.setQuoted(it) }
        return MessageContent.newBuilder().setText(textMsg).build().toByteArray()
    }

    /** The quote for [reply], as a text or an album carries it. */
    fun quoted(reply: ReplyRef?): QuotedMessage? {
        if (reply == null) return null
        val quoted = QuotedMessage.newBuilder().setMessageId(reply.messageId)
        val preview = reply.preview.take(ReplyRef.MAX_CHARS)
        if (preview.isNotEmpty()) quoted.setTextPreview(preview)
        mediaType(reply.mediaType)?.let { quoted.setMediaType(it) }
        return quoted.build()
    }

    private fun mediaType(name: String?): MediaType? {
        if (name.isNullOrEmpty()) return null
        val type = runCatching { MediaType.valueOf(name) }.getOrNull() ?: return null
        if (type == MediaType.MEDIA_TYPE_UNSPECIFIED || type == MediaType.UNRECOGNIZED) return null
        return type
    }
}
