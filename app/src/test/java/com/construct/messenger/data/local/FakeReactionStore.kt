package com.construct.messenger.data.local

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** [ReactionStore] in a map, for tests; [ReactionStoreContract] holds it to Room's behaviour. */
internal class FakeReactionStore(private val messages: MessageStore = FakeMessageStore()) : ReactionStore {
    val rows = linkedMapOf<Pair<String, String>, ReactionRecord>()

    override fun observeChat(chatId: String): Flow<List<ReactionRecord>> = flow {
        emit(rows.values.filter { messages.get(it.targetMessageId)?.chatId == chatId }.sortedBy { it.timestampMs })
    }

    override suspend fun get(targetMessageId: String, reactorUserId: String): ReactionRecord? = rows[targetMessageId to reactorUserId]

    override suspend fun put(reaction: ReactionRecord) {
        rows[reaction.targetMessageId to reaction.reactorUserId] = reaction
    }

    override suspend fun delete(targetMessageId: String, reactorUserId: String) {
        rows.remove(targetMessageId to reactorUserId)
    }

    override suspend fun deleteOrphansBefore(cutoffMs: Long) {
        val orphans = rows.filterValues { it.receivedAtMs <= cutoffMs && messages.get(it.targetMessageId) == null }.keys
        rows.keys.removeAll(orphans)
    }
}
