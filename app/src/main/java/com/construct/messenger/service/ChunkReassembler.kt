package com.construct.messenger.service

import com.construct.messenger.data.local.db.PendingChunkDao
import com.construct.messenger.data.local.db.PendingChunkEntity
import com.construct.messenger.diagnostics.Log
import com.construct.messenger.util.KnstFrame
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Puts a message sent as several KNST frames back together.
 *
 * **Canon:** iOS `ChunkedMessageReassembler` + `PendingReassemblyStore`,
 * `decisions/durable-chunk-reassembly.md`. A chunk's ratchet key is spent when it is decrypted, so
 * a chunk the server delivers again after a restart cannot be read twice: the decrypted bytes are
 * in Room before the envelope is acknowledged, or the message is gone. Hence a table, not a map.
 *
 * Until 2026-09-30 Android hid every frame of a message that did not fit one: a long text, and
 * nearly every attachment, from iOS never appeared.
 */
@Singleton
class ChunkReassembler @Inject constructor(
    private val dao: PendingChunkDao,
) {
    sealed interface Assembly {
        /**
         * The message whole: the bytes as they came when they were one frame (or no frame), else
         * one frame rebuilt around the joined payload — so every reader downstream sees a single
         * frame, as it always did.
         */
        class Ready(val plaintext: ByteArray) : Assembly

        /** Held; more chunks are to come. The envelope that brought it is done with. */
        data object Pending : Assembly

        data class Invalid(val reason: String) : Assembly
    }

    suspend fun accept(senderId: String, plaintext: ByteArray, nowMs: Long = System.currentTimeMillis()): Assembly {
        val expired = dao.pruneOlderThan(nowMs - RETENTION_MS)
        if (expired > 0) Log.w(TAG, "dropped $expired chunk(s) of messages that never completed in 24 h")

        val chunk = KnstFrame.parse(plaintext) ?: return Assembly.Ready(plaintext)
        if (chunk.total <= 1) return Assembly.Ready(plaintext)
        if (chunk.total > KnstFrame.MAX_CHUNKS) return Assembly.Invalid("${chunk.total} chunks, over ${KnstFrame.MAX_CHUNKS}")
        if (chunk.index >= chunk.total) return Assembly.Invalid("chunk ${chunk.index} of ${chunk.total}")
        if (chunk.length < 0 || chunk.length.toLong() > chunk.total.toLong() * KnstFrame.MAX_PAYLOAD) {
            return Assembly.Invalid("length ${chunk.length} for ${chunk.total} chunks")
        }

        val id = chunk.messageId.toString()
        val held = dao.chunks(senderId, id)
        val first = held.firstOrNull()
        if (first != null &&
            (first.totalChunks != chunk.total || first.plaintextLength != chunk.length || first.contentType != chunk.contentType)
        ) {
            // Two messages cannot share an id; the frames disagree, so what is held is not this one.
            Log.w(TAG, "chunks of ${id.take(8)}… disagree on their header — starting over")
            dao.delete(senderId, id)
        }
        dao.insert(
            PendingChunkEntity(
                senderId = senderId,
                messageId = id,
                chunkIndex = chunk.index,
                totalChunks = chunk.total,
                plaintextLength = chunk.length,
                contentType = chunk.contentType,
                payload = chunk.payload,
                receivedAtMs = nowMs,
            ),
        )
        val all = dao.chunks(senderId, id)
        if (all.size < chunk.total) return Assembly.Pending

        dao.delete(senderId, id)
        val joined = java.io.ByteArrayOutputStream(chunk.length)
        all.forEach { joined.write(it.payload) }
        val bytes = joined.toByteArray()
        if (bytes.size < chunk.length) return Assembly.Invalid("${bytes.size} bytes for a length of ${chunk.length}")
        Log.i(TAG, "${id.take(8)}… complete — ${chunk.total} chunks, ${chunk.length} B")
        return Assembly.Ready(KnstFrame.whole(bytes.copyOf(chunk.length), chunk.contentType, chunk.messageId))
    }

    companion object {
        private const val TAG = "ChunkReassembler"

        /** iOS: long enough to outlive a killed app coming back, short enough not to hoard. */
        const val RETENTION_MS = 24 * 60 * 60 * 1000L
    }
}
