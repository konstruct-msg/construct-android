package com.construct.messenger.util

import java.util.UUID
import uniffi.construct_core.knstEncodeChunks
import uniffi.construct_core.knstFrameWhole
import uniffi.construct_core.knstParse

/**
 * KNST wire frame — 30-byte header + payload, written and read by the core since 0.31
 * (`knst_encode_chunks`, `knst_frame_whole`, `knst_parse`; TODO 94 step 3a). One writer for iOS,
 * Android and the TUI; the format is fixed by construct-protos `knst_frame.json`.
 *
 * **Canon:** iOS `ChunkedMessageCodec` / `architecture/WIRE_FORMAT.md`.
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
 * Until 2026-10-02 this object wrote and read the header itself. [MAX_PAYLOAD] and [MAX_CHUNKS]
 * stay here because callers budget by them; `KnstFrameConformanceTest` holds them to the vectors
 * the core splits by. Receiving puts chunks back together in `service/ChunkReassembler`.
 */
object KnstFrame {
    const val HEADER_SIZE = IncomingPlaintext.HEADER_SIZE
    const val MAX_PAYLOAD = 3770
    const val VERSION: Byte = 0x01

    /** Regular 1:1 body — matches iOS `ChunkedMessageSender.buildPlan` default. */
    const val TYPE_E2EE_SIGNAL = 1

    /** Our own copy to a sibling device: iOS frames `SSR1 ‖ content` with this type. */
    const val TYPE_SENDER_SYNC = 23
    /** A `WebRTCSignal` (`calls/CallSignalWire`). Inside the ciphertext — nothing outside says "call". */
    const val TYPE_CALL_SIGNAL = 12

    /** iOS `ChunkedDeliveryConfig.maxChunks`: 256 × 3770 B, a little under 1 MB of plaintext. */
    const val MAX_CHUNKS = 256

    /** One whole frame; refuses a payload over [MAX_PAYLOAD] rather than truncating it. */
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
        checkNotNull(knstFrameWhole(payload, contentType.toUByte(), messageId.toString())) {
            "the core refused a frame for $messageId"
        }

    /**
     * [payload] as frames of at most [MAX_PAYLOAD] bytes each; one frame when it fits, identical
     * to [pack]. Every frame carries the whole length and the same id, so a reader can tell when
     * it holds them all.
     */
    fun chunks(payload: ByteArray, contentType: Int, messageId: UUID): List<ByteArray> =
        requireNotNull(knstEncodeChunks(payload, contentType.toUByte(), messageId.toString())) {
            "KNST payload ${payload.size} needs more than $MAX_CHUNKS chunks"
        }

    /** One frame's header, read. Null for anything that is not a v1 KNST frame. */
    data class Chunk(
        val contentType: Int,
        val messageId: UUID,
        val index: Int,
        val total: Int,
        /** The whole message's length, declared by every chunk of it. */
        val length: Int,
        /** Everything after the header. */
        val payload: ByteArray,
    ) {
        /**
         * The body of a single-chunk frame: the first [length] bytes, padding cut off. Null when
         * the declared length runs past what the frame holds.
         */
        fun body(): ByteArray? = if (length in 0..payload.size) payload.copyOf(length) else null
    }

    fun parse(bytes: ByteArray): Chunk? {
        val frame = knstParse(bytes) ?: return null
        return Chunk(
            contentType = frame.contentType.toInt(),
            messageId = UUID.fromString(frame.messageId),
            index = frame.chunkIndex.toInt(),
            total = frame.totalChunks.toInt(),
            length = frame.plaintextLength.toLong().coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            payload = frame.payload,
        )
    }
}
