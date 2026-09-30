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
 * [pack] is one whole frame and refuses a payload over [MAX_PAYLOAD] rather than truncating it;
 * [chunks] cuts any payload up to [MAX_CHUNKS] frames, as iOS `ChunkedMessageCodec.encodeChunks`
 * does. Receiving puts them back together in `service/ChunkReassembler`.
 */
object KnstFrame {
    const val HEADER_SIZE = IncomingPlaintext.HEADER_SIZE
    const val MAX_PAYLOAD = 3770
    const val VERSION: Byte = 0x01

    /** Regular 1:1 body — matches iOS `ChunkedMessageSender.buildPlan` default. */
    const val TYPE_E2EE_SIGNAL = 1

    /** Our own copy to a sibling device: iOS frames `SSR1 ‖ content` with this type. */
    const val TYPE_SENDER_SYNC = 23

    /** iOS `ChunkedDeliveryConfig.maxChunks`: 256 × 3770 B, a little under 1 MB of plaintext. */
    const val MAX_CHUNKS = 256

    fun pack(payload: ByteArray, contentType: Int, messageId: UUID): ByteArray {
        require(payload.size <= MAX_PAYLOAD) {
            "KNST payload ${payload.size} exceeds $MAX_PAYLOAD — use chunks()"
        }
        return whole(payload, contentType, messageId)
    }

    /**
     * One frame holding [payload] whatever its size — iOS `frameWhole`. Not for the wire past
     * [MAX_PAYLOAD]: it is how a reassembled message is handed on as the single frame it was.
     */
    fun whole(payload: ByteArray, contentType: Int, messageId: UUID): ByteArray =
        frame(payload, 0, payload.size, contentType, messageId, index = 0, total = 1, length = payload.size)

    /**
     * [payload] as frames of at most [MAX_PAYLOAD] bytes each; one frame when it fits, identical
     * to [pack]. Every frame carries the whole length and the same id, so a reader can tell when
     * it holds them all.
     */
    fun chunks(payload: ByteArray, contentType: Int, messageId: UUID): List<ByteArray> {
        val total = maxOf(1, (payload.size + MAX_PAYLOAD - 1) / MAX_PAYLOAD)
        require(total <= MAX_CHUNKS) { "KNST payload ${payload.size} needs $total chunks, over $MAX_CHUNKS" }
        return List(total) { i ->
            val start = i * MAX_PAYLOAD
            val end = minOf(start + MAX_PAYLOAD, payload.size)
            frame(payload, start, end, contentType, messageId, index = i, total = total, length = payload.size)
        }
    }

    /** One frame's header, read. Null for anything that is not a v1 KNST frame. */
    data class Chunk(
        val contentType: Int,
        val messageId: UUID,
        val index: Int,
        val total: Int,
        val length: Int,
        val payload: ByteArray,
    )

    fun parse(bytes: ByteArray): Chunk? {
        if (!IncomingPlaintext.isKnst(bytes) || bytes[4] != VERSION) return null
        val buf = ByteBuffer.wrap(bytes)
        val id = UUID(buf.getLong(6), buf.getLong(14))
        val index = buf.getShort(22).toInt() and 0xFFFF
        val total = buf.getShort(24).toInt() and 0xFFFF
        val length = buf.getInt(26)
        return Chunk(
            contentType = bytes[5].toInt() and 0xFF,
            messageId = id,
            index = index,
            total = total,
            length = length,
            payload = bytes.copyOfRange(HEADER_SIZE, bytes.size),
        )
    }

    private fun frame(
        payload: ByteArray,
        start: Int,
        end: Int,
        contentType: Int,
        messageId: UUID,
        index: Int,
        total: Int,
        length: Int,
    ): ByteArray {
        val out = ByteArray(HEADER_SIZE + (end - start))
        out[0] = 'K'.code.toByte()
        out[1] = 'N'.code.toByte()
        out[2] = 'S'.code.toByte()
        out[3] = 'T'.code.toByte()
        out[4] = VERSION
        out[5] = contentType.toByte()
        val idBytes = uuidBytes(messageId)
        System.arraycopy(idBytes, 0, out, 6, 16)
        out[22] = (index ushr 8).toByte()
        out[23] = index.toByte()
        out[24] = (total ushr 8).toByte()
        out[25] = total.toByte()
        out[26] = (length ushr 24).toByte()
        out[27] = (length ushr 16).toByte()
        out[28] = (length ushr 8).toByte()
        out[29] = length.toByte()
        System.arraycopy(payload, start, out, HEADER_SIZE, end - start)
        return out
    }

    fun uuidBytes(id: UUID): ByteArray {
        val buf = ByteBuffer.allocate(16)
        buf.putLong(id.mostSignificantBits)
        buf.putLong(id.leastSignificantBits)
        return buf.array()
    }
}
