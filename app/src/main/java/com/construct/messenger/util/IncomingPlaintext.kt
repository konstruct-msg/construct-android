package com.construct.messenger.util

import com.construct.messenger.data.model.ReplyRef
import com.construct.messenger.stickers.StickerReference
import shared.proto.core.v1.EnvelopeOuterClass.ContentType
import shared.proto.messaging.v1.Content.DeleteScope
import shared.proto.messaging.v1.Content.MediaType
import shared.proto.messaging.v1.Content.MessageContent
import shared.proto.messaging.v1.Content.MessageContent.ContentCase
import shared.proto.messaging.v1.Content.QuotedMessage
import shared.proto.messaging.v1.Content.TextMessage

/**
 * Turns a decrypted Double-Ratchet plaintext into display text, or into an
 * edit / delete / reaction on a message already in the transcript.
 *
 * After decrypt the blob is untyped. Recipients sniff four formats
 * (`architecture/WIRE_FORMAT.md`): KNST frame, bare `MessageContent` proto,
 * binary profile-share, legacy UTF-8. Magic `"KNST"` answers the first
 * question. A message sent as several frames reaches here already joined into one
 * (`service/ChunkReassembler`); a lone chunk that did not is never rendered as a bubble.
 *
 * An edit or a delete-for-everyone is not a bubble. The row they name is the
 * UUID in the original message's KNST header (`e2eMessageId`), never the
 * envelope id the server may rewrite.
 */
object IncomingPlaintext {

    data class Edit(val targetMessageId: String, val newText: String)

    /** Delete-for-everyone. Delete-for-self is not applied: it never leaves the sender's phone. */
    data class Delete(val targetMessageId: String)

    /** `MessageContent.reaction` as sent — validated by `ReactionRules` where it is applied. */
    data class Reaction(val targetMessageId: String, val actionRawValue: Int, val emoji: String, val timestampMs: Long)

    data class Decoded(
        val text: String,
        val knstContentType: Int,
        val isUserVisible: Boolean,
        /** Set when the plaintext text message quotes another. Absent for legacy UTF-8. */
        val reply: ReplyRef? = null,
        /** Sender's id from KNST bytes 6..21. Null for a legacy blob or the nil UUID. */
        val e2eMessageId: String? = null,
        val edit: Edit? = null,
        val delete: Delete? = null,
        /** Metadata on another message, never a bubble. */
        val reaction: Reaction? = null,
        /**
         * A contact's profile in the untyped v1 layout — applied to their row, never a bubble.
         * Read for one release after type 29 (`ProfileShare`); nothing sends it any more.
         */
        val legacyProfile: LegacyProfileShare? = null,
        /** Photos, videos, files or a voice note; [text] is then the caption, possibly empty. */
        val media: MediaWire.Stored? = null,
    )

    fun decode(plaintext: ByteArray): Decoded {
        if (isKnst(plaintext)) {
            // Read by the core since 0.31 (`KnstFrame.parse`). Magic it cannot read as a v1 frame
            // is never a bubble: it is not text either.
            val frame = KnstFrame.parse(plaintext) ?: return hidden(0, null)
            val type = frame.contentType
            // The nil id is not an identity: a frame that never set one falls back to the envelope id.
            val e2e = frame.messageId.toString().takeUnless { it == NIL_MESSAGE_ID }
            if (type.isControlType() || frame.index != 0 || frame.total > 1) {
                return hidden(type, e2e)
            }
            val payload = frame.body() ?: return hidden(type, e2e)
            // iOS `decodeAssembled`: MessageContent, then a binary profile, then text. A profile
            // read as text was hidden only while its timestamp bytes happened not to be UTF-8.
            return decodePayload(payload, type, e2e)
                ?: LegacyProfileShare.decode(payload)?.let { hidden(type, e2e).copy(legacyProfile = it) }
                ?: framedText(payload, type, e2e)
                ?: hidden(type, e2e)
        }
        decodePayload(plaintext, knstContentType = 0, e2eMessageId = null)?.let { return it }
        LegacyProfileShare.decode(plaintext)?.let { return hidden(0, null).copy(legacyProfile = it) }
        val utf8 = plaintext.toString(Charsets.UTF_8)
        return Decoded(utf8, knstContentType = 0, isUserVisible = utf8.isNotEmpty())
    }

    /**
     * A frame whose payload is bare UTF-8 rather than `MessageContent` — what iOS resends after a
     * decryption error (`SessionCoordinator.resendAfterDecryptionError` frames the stored text as
     * is). iOS reads it (`decodeAssembled` ends in the same fallback); Android hid it, so the one
     * message a peer resent because this device had lost its state was lost a second time.
     */
    private fun framedText(payload: ByteArray, type: Int, e2e: String?): Decoded? {
        val text = runCatching {
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(payload)).toString()
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        return Decoded(text = text, knstContentType = type, isUserVisible = true, e2eMessageId = e2e)
    }

    fun isKnst(bytes: ByteArray): Boolean =
        bytes.size >= HEADER_SIZE &&
            bytes[0] == 'K'.code.toByte() &&
            bytes[1] == 'N'.code.toByte() &&
            bytes[2] == 'S'.code.toByte() &&
            bytes[3] == 'T'.code.toByte()

    private fun hidden(type: Int, e2e: String?) =
        Decoded(text = "", knstContentType = type, isUserVisible = false, e2eMessageId = e2e)

    /**
     * Null when these bytes are not a `MessageContent` we recognise, so a bare
     * legacy string can still be tried. A recognised payload that is not shown
     * (edit, reaction, sticker) returns a non-visible [Decoded] instead, so it is
     * never shown as mojibake.
     */
    private fun decodePayload(payload: ByteArray, knstContentType: Int, e2eMessageId: String?): Decoded? =
        try {
            val content = MessageContent.parseFrom(payload)
            when {
                content.hasEdit() -> {
                    val target = content.edit.targetMessageId.trim()
                    if (target.isEmpty()) {
                        hidden(knstContentType, e2eMessageId)
                    } else {
                        val newText = if (content.edit.hasNewText()) content.edit.newText.text else ""
                        hidden(knstContentType, e2eMessageId).copy(edit = Edit(target, newText))
                    }
                }
                content.hasDelete() -> {
                    val target = content.delete.targetMessageId.trim()
                    val everyone = content.delete.scope == DeleteScope.DELETE_SCOPE_EVERYONE
                    if (target.isEmpty() || !everyone) {
                        hidden(knstContentType, e2eMessageId)
                    } else {
                        hidden(knstContentType, e2eMessageId).copy(delete = Delete(target))
                    }
                }
                content.hasReaction() -> hidden(knstContentType, e2eMessageId).copy(
                    reaction = content.reaction.let {
                        Reaction(it.targetMessageId, it.actionValue, it.emoji, it.timestampMs)
                    },
                )
                // A reference, never pixels. One that fails the wire rules is a corrupt message:
                // not shown, not stored (iOS `StickerReference(wire:)`).
                content.hasSticker() -> StickerReference.fromWire(content.sticker)?.let { ref ->
                    Decoded(
                        text = "",
                        knstContentType = knstContentType,
                        isUserVisible = true,
                        e2eMessageId = e2eMessageId,
                        media = MediaWire.sticker(ref),
                    )
                } ?: hidden(knstContentType, e2eMessageId)
                content.hasText() && content.text.text.isNotEmpty() -> Decoded(
                    text = content.text.text,
                    knstContentType = knstContentType,
                    isUserVisible = true,
                    reply = replyOf(content.text),
                    e2eMessageId = e2eMessageId,
                )
                MediaWire.stored(content) != null -> {
                    val media = MediaWire.stored(content)!!
                    Decoded(
                        text = media.caption,
                        knstContentType = knstContentType,
                        isUserVisible = true,
                        reply = MediaWire.albumQuote(content)?.let(::replyOf),
                        e2eMessageId = e2eMessageId,
                        media = media,
                    )
                }
                // Empty text is not a message. Unknown fields on a legacy string are not one either:
                // protobuf will parse "hi" and report a size, and that must stay the word hi.
                content.hasText() || content.contentCase == ContentCase.CONTENT_NOT_SET -> null
                else -> hidden(knstContentType, e2eMessageId)
            }
        } catch (_: Exception) {
            null
        }

    /** The quote iOS put on the text. An empty message id is not a reply. */
    private fun replyOf(text: TextMessage): ReplyRef? {
        if (!text.hasQuoted()) return null
        return replyOf(text.quoted)
    }

    /** The quote a text or an album carries; an empty message id is not a reply. */
    internal fun replyOf(quoted: QuotedMessage): ReplyRef? {
        val media = if (quoted.hasMediaType()) {
            quoted.mediaType
                .takeIf { it != MediaType.MEDIA_TYPE_UNSPECIFIED && it != MediaType.UNRECOGNIZED }
                ?.name
        } else {
            null
        }
        val preview = if (quoted.hasTextPreview()) quoted.textPreview else ""
        return ReplyRef.of(quoted.messageId, preview, media)
    }

    private fun Int.isControlType(): Boolean = when (this) {
        ContentType.CONTENT_TYPE_CALL_SIGNAL_VALUE,
        ContentType.CONTENT_TYPE_HEARTBEAT_VALUE,
        ContentType.CONTENT_TYPE_DELIVERY_RECEIPT_VALUE,
        ContentType.CONTENT_TYPE_KEY_EXCHANGE_VALUE,
        ContentType.CONTENT_TYPE_SESSION_RESET_VALUE,
        ContentType.CONTENT_TYPE_KEY_SYNC_VALUE,
        ContentType.CONTENT_TYPE_SENDER_SYNC_VALUE,
        ContentType.CONTENT_TYPE_SESSION_RESET_INIT_VALUE,
        ContentType.CONTENT_TYPE_SESSION_PING_VALUE,
        ContentType.CONTENT_TYPE_SESSION_READY_VALUE,
        // A `ProfileShare`, read where it is applied — never as a message's content.
        ContentType.CONTENT_TYPE_PROFILE_VALUE,
        -> true
        else -> false
    }

    const val HEADER_SIZE = 30
    private const val NIL_MESSAGE_ID = "00000000-0000-0000-0000-000000000000"
}
