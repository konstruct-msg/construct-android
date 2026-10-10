package com.construct.messenger.data.local

import com.construct.messenger.data.local.db.ReactionDao
import com.construct.messenger.data.local.db.ReactionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * [ReactionStore] on Room's `reactions` table — until the core's `LocalStore` takes it (TODO 136).
 * Built by `DatabaseModule`, which provides no `ReactionDao`: nothing else can reach the table.
 */
class RoomReactionStore(private val dao: ReactionDao) : ReactionStore {
    override fun observeChat(chatId: String): Flow<List<ReactionRecord>> =
        dao.observeChat(chatId).map { rows -> rows.map { it.record() } }

    override suspend fun get(targetMessageId: String, reactorUserId: String): ReactionRecord? =
        dao.get(targetMessageId, reactorUserId)?.record()

    override suspend fun put(reaction: ReactionRecord) = dao.upsert(
        ReactionEntity(reaction.targetMessageId, reaction.reactorUserId, reaction.emoji, reaction.timestampMs, reaction.receivedAtMs),
    )

    override suspend fun delete(targetMessageId: String, reactorUserId: String) = dao.delete(targetMessageId, reactorUserId)

    override suspend fun deleteOrphansBefore(cutoffMs: Long) = dao.deleteOrphansBefore(cutoffMs)
}

private fun ReactionEntity.record() = ReactionRecord(targetMessageId, reactorUserId, emoji, timestampMs, receivedAtMs)
