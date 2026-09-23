package com.construct.messenger.util

import shared.proto.core.v1.EnvelopeOuterClass.ContentType
import shared.proto.messaging.v1.Content.MessageContent

/**
 * Turns a decrypted Double-Ratchet plaintext into display text.
 *
 * After decrypt the blob is untyped. Recipients sniff four formats
 * (`architecture/WIRE_FORMAT.md`): KNST frame, bare `MessageContent` proto,
 * binary profile-share, legacy UTF-8. Magic `"KNST"` answers the first
 * question. Chunk reassembly (total_chunks > 1) is a later phase — a partial
 * first chunk is not rendered as a bubble.
 */
object IncomingPlaintext {

    data class Decoded(
        val text: String,
        val knstContentType: Int,
        val isUserVisible: Boolean,
    )

    fun decode(plaintext: ByteArray): Decoded {
        if (isKnst(plaintext)) {
            val type = plaintext[5].toInt() and 0xFF
            if (type.isControlType() || !isSingleCompleteChunk(plaintext)) {
                return Decoded(text = "", knstContentType = type, isUserVisible = false)
            }
            val payload = knstPayload(plaintext) ?: return Decoded("", type, isUserVisible = false)
            val text = decodeInner(payload)
            return Decoded(text = text, knstContentType = type, isUserVisible = text.isNotEmpty())
        }
        val asProto = decodeInner(plaintext)
        if (asProto.isNotEmpty()) {
            return Decoded(asProto, knstContentType = 0, isUserVisible = true)
        }
        val utf8 = plaintext.toString(Charsets.UTF_8)
        return Decoded(utf8, knstContentType = 0, isUserVisible = utf8.isNotEmpty())
    }

    fun isKnst(bytes: ByteArray): Boolean =
        bytes.size >= HEADER_SIZE &&
            bytes[0] == 'K'.code.toByte() &&
            bytes[1] == 'N'.code.toByte() &&
            bytes[2] == 'S'.code.toByte() &&
            bytes[3] == 'T'.code.toByte()

    private fun isSingleCompleteChunk(bytes: ByteArray): Boolean {
        val chunkIndex = u16(bytes, 22)
        val totalChunks = u16(bytes, 24)
        return chunkIndex == 0 && totalChunks <= 1
    }

    private fun knstPayload(bytes: ByteArray): ByteArray? {
        val declared = u32(bytes, 26)
        val end = HEADER_SIZE + declared
        if (end > bytes.size) return null
        return bytes.copyOfRange(HEADER_SIZE, end)
    }

    private fun decodeInner(payload: ByteArray): String = try {
        val content = MessageContent.parseFrom(payload)
        if (content.hasText()) content.text.text else ""
    } catch (_: Exception) {
        ""
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
        -> true
        else -> false
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    private fun u32(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    const val HEADER_SIZE = 30
}
