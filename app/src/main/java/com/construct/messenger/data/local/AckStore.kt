package com.construct.messenger.data.local

import com.construct.messenger.data.local.db.AckDao
import com.construct.messenger.data.local.db.AckedMessageEntity
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Durable "already processed" registry for incoming messages.
 *
 * Port of iOS `PersistentACKStore`: a Room-backed set with an in-memory mirror.
 * The hot path ([isProcessed]) is a synchronous memory read — the server offline
 * queue re-delivers batches on every reconnect, so dedup must not pay a DB
 * round-trip per message.
 *
 * **Lifecycle:** [hydrate] must be called once after login, BEFORE the message
 * stream / catch-up starts processing (storm-hardening invariant #6 requires
 * restored state to be live before queued control messages are handled).
 * Writes go to both the DB and the mirror immediately.
 */
interface AckStore {
    /** Loads all persisted ids into memory. Idempotent. */
    suspend fun hydrate()

    /** Synchronous in-memory check (iOS `isProcessedInMemory`). */
    fun isProcessed(messageId: String): Boolean

    /** Records [messageId] as processed (DB + memory). */
    suspend fun markProcessed(messageId: String, senderId: String)

    /** Drops rows older than [olderThanMs] epoch-ms; returns rows deleted. */
    suspend fun prune(olderThanMs: Long): Int
}

@Singleton
class PersistentAckStore @Inject constructor(
    private val ackDao: AckDao,
) : AckStore {

    private val memory = ConcurrentHashMap.newKeySet<String>()

    override suspend fun hydrate() {
        memory.addAll(ackDao.getAllIds())
    }

    override fun isProcessed(messageId: String): Boolean = messageId in memory

    override suspend fun markProcessed(messageId: String, senderId: String) {
        ackDao.insert(
            AckedMessageEntity(
                messageId = messageId,
                senderId = senderId,
                processedAtMs = System.currentTimeMillis(),
            ),
        )
        memory.add(messageId)
    }

    override suspend fun prune(olderThanMs: Long): Int {
        val deleted = ackDao.pruneOlderThan(olderThanMs)
        if (deleted > 0) {
            // Re-hydrate rather than tracking which ids aged out — prune is rare.
            memory.clear()
            memory.addAll(ackDao.getAllIds())
        }
        return deleted
    }
}
