package com.construct.messenger.data.local

import com.construct.messenger.data.local.db.PendingChunkDao
import com.construct.messenger.data.local.db.PendingChunkEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.construct_core.LocalPendingChunk
import uniffi.construct_core.LocalStore

/** One decrypted KNST chunk of a message not yet complete, keyed by sender, message and index. */
class PendingChunk(
    val senderId: String,
    val messageId: String,
    val chunkIndex: Int,
    val totalChunks: Int,
    val plaintextLength: Int,
    val contentType: Int,
    val payload: ByteArray,
    val receivedAtMs: Long,
)

/**
 * Where [com.construct.messenger.service.ChunkReassembler] holds chunks between envelopes
 * (TODO 136). [PendingChunkStoreContract] holds Room's and the core's to one behaviour.
 */
interface PendingChunkStore {
    /** A redelivered chunk keeps the copy already held. */
    suspend fun add(chunk: PendingChunk)

    /** One sender's message, by chunk index. */
    suspend fun chunks(senderId: String, messageId: String): List<PendingChunk>

    suspend fun delete(senderId: String, messageId: String)

    /** Forgets chunks received before [cutoffMs]; returns how many. */
    suspend fun forgetBefore(cutoffMs: Long): Int
}

/** Built by `DatabaseModule`, which provides no `PendingChunkDao`. */
class RoomPendingChunkStore(private val dao: PendingChunkDao) : PendingChunkStore {
    override suspend fun add(chunk: PendingChunk) = with(chunk) {
        dao.insert(PendingChunkEntity(senderId, messageId, chunkIndex, totalChunks, plaintextLength, contentType, payload, receivedAtMs))
    }

    override suspend fun chunks(senderId: String, messageId: String): List<PendingChunk> =
        dao.chunks(senderId, messageId).map {
            PendingChunk(it.senderId, it.messageId, it.chunkIndex, it.totalChunks, it.plaintextLength, it.contentType, it.payload, it.receivedAtMs)
        }

    override suspend fun delete(senderId: String, messageId: String) = dao.delete(senderId, messageId)

    override suspend fun forgetBefore(cutoffMs: Long): Int = dao.pruneOlderThan(cutoffMs)
}

/** On the core's `LocalStore`, which keeps the same table. Not wired into the app yet. */
class CorePendingChunkStore(private val store: LocalStore) : PendingChunkStore {
    override suspend fun add(chunk: PendingChunk) {
        withContext(Dispatchers.IO) {
            store.addPendingChunk(
                with(chunk) {
                    LocalPendingChunk(
                        senderId = senderId,
                        messageId = messageId,
                        chunkIndex = chunkIndex.toUInt(),
                        totalChunks = totalChunks.toUInt(),
                        plaintextLength = plaintextLength.toULong(),
                        contentType = contentType.toUByte(),
                        payload = payload,
                        envelopeId = null,
                        receivedAt = receivedAtMs,
                    )
                },
            )
        }
    }

    override suspend fun chunks(senderId: String, messageId: String): List<PendingChunk> = withContext(Dispatchers.IO) {
        store.pendingChunks(senderId, messageId).map {
            PendingChunk(
                it.senderId, it.messageId, it.chunkIndex.toInt(), it.totalChunks.toInt(), it.plaintextLength.toInt(),
                it.contentType.toInt(), it.payload, it.receivedAt,
            )
        }
    }

    override suspend fun delete(senderId: String, messageId: String) {
        withContext(Dispatchers.IO) { store.deletePendingChunks(senderId, messageId) }
    }

    override suspend fun forgetBefore(cutoffMs: Long): Int =
        withContext(Dispatchers.IO) { store.forgetPendingChunksBefore(cutoffMs).toInt() }
}
