package com.construct.messenger.data.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.construct_core.LocalStore

/**
 * The envelopes already processed, kept across restarts (TODO 136) — what [PersistentAckStore]
 * mirrors in memory. Exact ids. [ProcessedStoreContract] holds the stores to one behaviour.
 */
interface ProcessedStore {
    /** Records [messageId]; one already held keeps its first time. */
    suspend fun record(messageId: String, senderId: String, processedAtMs: Long)

    /** Every id held — to warm the mirror at launch. */
    suspend fun ids(): List<String>

    /** Forgets ids processed before [cutoffMs]; returns how many. */
    suspend fun forgetBefore(cutoffMs: Long): Int
}

/** On the core's `LocalStore`, which keeps the same table. */
class CoreProcessedStore(private val store: LocalStore) : ProcessedStore {
    override suspend fun record(messageId: String, senderId: String, processedAtMs: Long) {
        withContext(Dispatchers.IO) { store.markProcessed(messageId, senderId, processedAtMs) }
    }

    override suspend fun ids(): List<String> = withContext(Dispatchers.IO) { store.processedMessageIds() }

    override suspend fun forgetBefore(cutoffMs: Long): Int =
        withContext(Dispatchers.IO) { store.forgetProcessedBefore(cutoffMs).toInt() }
}
