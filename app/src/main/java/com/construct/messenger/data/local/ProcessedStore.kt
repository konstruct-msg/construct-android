package com.construct.messenger.data.local

import com.construct.messenger.data.local.db.AckDao
import com.construct.messenger.data.local.db.AckedMessageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.construct_core.LocalStore

/**
 * The envelopes already processed, kept across restarts (TODO 136) — what [PersistentAckStore]
 * mirrors in memory. Exact ids. [ProcessedStoreContract] holds Room's and the core's to one behaviour.
 */
interface ProcessedStore {
    /** Records [messageId]; one already held keeps its first time. */
    suspend fun record(messageId: String, senderId: String, processedAtMs: Long)

    /** Every id held — to warm the mirror at launch. */
    suspend fun ids(): List<String>

    /** Forgets ids processed before [cutoffMs]; returns how many. */
    suspend fun forgetBefore(cutoffMs: Long): Int
}

/** Built by `DatabaseModule`, which provides no `AckDao`. */
class RoomProcessedStore(private val dao: AckDao) : ProcessedStore {
    override suspend fun record(messageId: String, senderId: String, processedAtMs: Long) =
        dao.insert(AckedMessageEntity(messageId, senderId, processedAtMs))

    override suspend fun ids(): List<String> = dao.getAllIds()

    override suspend fun forgetBefore(cutoffMs: Long): Int = dao.pruneOlderThan(cutoffMs)
}

/** On the core's `LocalStore`, which keeps the same table. Not wired into the app yet. */
class CoreProcessedStore(private val store: LocalStore) : ProcessedStore {
    override suspend fun record(messageId: String, senderId: String, processedAtMs: Long) {
        withContext(Dispatchers.IO) { store.markProcessed(messageId, senderId, processedAtMs) }
    }

    override suspend fun ids(): List<String> = withContext(Dispatchers.IO) { store.processedMessageIds() }

    override suspend fun forgetBefore(cutoffMs: Long): Int =
        withContext(Dispatchers.IO) { store.forgetProcessedBefore(cutoffMs).toInt() }
}
