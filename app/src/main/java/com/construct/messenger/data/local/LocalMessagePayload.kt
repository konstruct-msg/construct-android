package com.construct.messenger.data.local

import com.construct.messenger.data.model.ReplyRef
import com.construct.messenger.util.IncomingPlaintext
import com.construct.messenger.util.MediaWire
import com.construct.messenger.util.TextWire
import shared.proto.messaging.v1.Content.MediaAlbumMessage
import shared.proto.messaging.v1.Content.MessageContent

/**
 * A message's body as the core's store keeps it: the **CTM1** envelope iOS writes
 * (`LocalMessagePayload.swift`, vault `client/specs/local-message-payload-binary.md`) — one format
 * for every client of the one store, so a database is the same whichever app wrote it (TODO 136).
 *
 * ```
 * 0…3  "CTM1"
 * 4    kind: 0x01 UTF-8 text · 0x02 MediaAlbumMessage · 0x03 MessageContent · 0x04 profile
 * 5…   the kind's bytes
 * ```
 * Bytes without the magic are a row from before the envelope — UTF-8 text.
 *
 * Android writes 0x03, the whole wire `MessageContent` — what iOS stores for everything it
 * receives and most of what it sends — so the quote and the media travel inside it. It reads every
 * kind, iOS's 0x02 album and 0x01 text included.
 */
object LocalMessagePayload {
    private val MAGIC = byteArrayOf(0x43, 0x54, 0x4D, 0x31) // "CTM1"
    const val KIND_TEXT: Byte = 0x01
    const val KIND_MEDIA_ALBUM: Byte = 0x02
    const val KIND_MESSAGE_CONTENT: Byte = 0x03
    const val KIND_PROFILE: Byte = 0x04

    /** What a body says, in the fields [MessageRecord] keeps. */
    data class Fields(
        val text: String,
        val reply: ReplyRef? = null,
        val mediaType: String? = null,
        val mediaPayload: ByteArray? = null,
    ) {
        override fun equals(other: Any?): Boolean =
            other is Fields && text == other.text && reply == other.reply && mediaType == other.mediaType &&
                mediaPayload.contentEquals(other.mediaPayload)

        override fun hashCode(): Int = 31 * (31 * text.hashCode() + (reply?.hashCode() ?: 0)) + mediaPayload.contentHashCode()
    }

    fun envelope(kind: Byte, body: ByteArray): ByteArray = MAGIC + kind + body

    /** The body for [record]: its media as the wire carries it, else its text with its quote. */
    fun encode(record: MessageRecord): ByteArray {
        val media = record.mediaType?.let { kind -> record.mediaPayload?.let { MediaWire.content(kind, it) } }
        if (media != null) return envelope(KIND_MESSAGE_CONTENT, media)
        val reply = record.replyToId?.let { ReplyRef(it, record.replyPreview.orEmpty(), record.replyMediaType) }
        return envelope(KIND_MESSAGE_CONTENT, TextWire.encode(record.text, reply))
    }

    /** What [body] says. A kind this build cannot read gives empty text, never an exception. */
    fun decode(body: ByteArray): Fields {
        if (body.size < MAGIC.size + 1 || !body.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            return Fields(String(body, Charsets.UTF_8))
        }
        val inner = body.copyOfRange(MAGIC.size + 1, body.size)
        return runCatching {
            when (body[MAGIC.size]) {
                KIND_TEXT -> Fields(String(inner, Charsets.UTF_8))
                KIND_MEDIA_ALBUM -> album(MediaAlbumMessage.parseFrom(inner))
                KIND_MESSAGE_CONTENT -> content(MessageContent.parseFrom(inner))
                else -> Fields("")
            }
        }.getOrDefault(Fields(""))
    }

    private fun album(album: MediaAlbumMessage) = Fields(
        text = if (album.hasCaption()) album.caption else "",
        reply = if (album.hasQuoted()) IncomingPlaintext.replyOf(album.quoted) else null,
        mediaType = MediaWire.KIND_ALBUM,
        mediaPayload = album.toByteArray(),
    )

    private fun content(content: MessageContent): Fields {
        if (content.hasText()) {
            val text = content.text
            return Fields(text.text, if (text.hasQuoted()) IncomingPlaintext.replyOf(text.quoted) else null)
        }
        if (content.hasSticker()) return Fields("", mediaType = MediaWire.KIND_STICKER, mediaPayload = content.sticker.toByteArray())
        val stored = MediaWire.stored(content) ?: return Fields("")
        if (stored.kind == MediaWire.KIND_ALBUM) return album(MediaAlbumMessage.parseFrom(stored.bytes))
        return Fields(stored.caption, mediaType = stored.kind, mediaPayload = stored.bytes)
    }
}
