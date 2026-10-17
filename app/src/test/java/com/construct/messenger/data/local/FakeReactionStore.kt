package com.construct.messenger.data.local

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** [ReactionStore] in a map, for tests; [ReactionStoreContract] holds it to Room's behaviour. */
internal class FakeReactionStore(private val messages: MessageStore = FakeMessageStore()) : ReactionStore {
    val rows = linkedMapOf<Pair<String, String>, ReactionRecord>()

    /** Bumped by every write through the store, as a table change is. */
    private val writes = MutableStateFlow(0)

    override fun observeChat(chatId: String): Flow<List<ReactionRecord>> = writes.map {
        rows.values.filter { messages.get(it.targetMessageId)?.chatId == chatId }.sortedBy { it.timestampMs }
    }

    override suspend fun get(targetMessageId: String, reactorUserId: String): ReactionRecord? = rows[targetMessageId to reactorUserId]

    override suspend fun put(reaction: ReactionRecord) {
        rows[reaction.targetMessageId to reaction.reactorUserId] = reaction
        writes.value++
    }

    override suspend fun delete(targetMessageId: String, reactorUserId: String) {
        rows.remove(targetMessageId to reactorUserId)
        writes.value++
    }

    override suspend fun deleteOrphansBefore(cutoffMs: Long) {
        val orphans = rows.filterValues { it.receivedAtMs <= cutoffMs && messages.get(it.targetMessageId) == null }.keys
        if (rows.keys.removeAll(orphans)) writes.value++
    }
}
