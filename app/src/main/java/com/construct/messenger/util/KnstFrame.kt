package com.construct.messenger.util

import java.nio.ByteBuffer
import java.util.UUID

/**
 * KNST wire frame — 30-byte header + payload.
 *
 * **Canon:** iOS `ChunkedMessageCodec.frameWhole` / `architecture/WIRE_FORMAT.md`.
 * Layout:
 * ```
 * [0..3]   magic "KNST"
 * [4]      version 0x01
 * [5]      content_type   ← inside the ciphertext, server cannot read
 * [6..21]  message_id (16 raw UUID bytes)
 * [22..23] chunk_index BE
 * [24..25] total_chunks BE
 * [26..29] plaintext_length BE
 * ```
 *
 * Multi-chunk reassembly is a later phase. [pack] refuses payloads larger than
 * [MAX_PAYLOAD] rather than silently truncating.
 */
object KnstFrame {
    const val HEADER_SIZE = IncomingPlaintext.HEADER_SIZE
    const val MAX_PAYLOAD = 3770
    const val VERSION: Byte = 0x01

    /** Regular 1:1 body — matches iOS `ChunkedMessageSender.buildPlan` default. */
    const val TYPE_E2EE_SIGNAL = 1

    fun pack(payload: ByteArray, contentType: Int, messageId: UUID): ByteArray {
        require(payload.size <= MAX_PAYLOAD) {
            "KNST payload ${payload.size} exceeds $MAX_PAYLOAD — chunking not implemented"
        }
        val out = ByteArray(HEADER_SIZE + payload.size)
        out[0] = 'K'.code.toByte()
        out[1] = 'N'.code.toByte()
        out[2] = 'S'.code.toByte()
        out[3] = 'T'.code.toByte()
        out[4] = VERSION
        out[5] = contentType.toByte()
        val idBytes = uuidBytes(messageId)
        System.arraycopy(idBytes, 0, out, 6, 16)
        // chunk_index = 0
        out[22] = 0
        out[23] = 0
        // total_chunks = 1
        out[24] = 0
        out[25] = 1
        val len = payload.size
        out[26] = (len ushr 24).toByte()
        out[27] = (len ushr 16).toByte()
        out[28] = (len ushr 8).toByte()
        out[29] = len.toByte()
        System.arraycopy(payload, 0, out, HEADER_SIZE, payload.size)
        return out
    }

    fun uuidBytes(id: UUID): ByteArray {
        val buf = ByteBuffer.allocate(16)
        buf.putLong(id.mostSignificantBits)
        buf.putLong(id.leastSignificantBits)
        return buf.array()
    }
}
