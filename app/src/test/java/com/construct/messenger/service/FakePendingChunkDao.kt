package com.construct.messenger.service

import com.construct.messenger.data.local.db.PendingChunkDao
import com.construct.messenger.data.local.db.PendingChunkEntity

internal class FakePendingChunkDao : PendingChunkDao {
    val rows = mutableListOf<PendingChunkEntity>()

    override suspend fun insert(chunk: PendingChunkEntity) {
        if (rows.none { it.senderId == chunk.senderId && it.messageId == chunk.messageId && it.chunkIndex == chunk.chunkIndex }) {
            rows += chunk
        }
    }

    override suspend fun chunks(senderId: String, messageId: String) =
        rows.filter { it.senderId == senderId && it.messageId == messageId }.sortedBy { it.chunkIndex }

    override suspend fun count(senderId: String, messageId: String) = chunks(senderId, messageId).size

    override suspend fun delete(senderId: String, messageId: String) {
        rows.removeAll { it.senderId == senderId && it.messageId == messageId }
    }

    override suspend fun pruneOlderThan(thresholdMs: Long): Int {
        val before = rows.size
        rows.removeAll { it.receivedAtMs < thresholdMs }
        return before - rows.size
    }
}
