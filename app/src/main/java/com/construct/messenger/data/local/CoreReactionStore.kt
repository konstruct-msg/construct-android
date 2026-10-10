package com.construct.messenger.data.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import uniffi.construct_core.LocalReaction
import uniffi.construct_core.LocalStore
import uniffi.construct_core.LocalStoreTable

/**
 * [ReactionStore] on the core's encrypted `LocalStore` (TODO 136) — held to the same
 * [ReactionStoreContract] as [RoomReactionStore]. Not wired into the app yet: Room stays the
 * store until the import moves its rows here. The row is iOS's: `received_at` is when this phone
 * stored the reaction, as iOS writes it.
 *
 * Two answers the core does not give yet, worked out here — correct, and slow on a long history;
 * core operations are to replace both before this store is switched on:
 * - a chat's reactions: [observeChat] asks for each of the chat's messages in turn;
 * - the orphan sweep: the core's `expire_reactions` forgets **every** reaction received before
 *   the cutoff, the ones on messages that are here too — Android and iOS forget only orphans. So
 *   [deleteOrphansBefore] reads them all and keeps any whose message exists.
 */
class CoreReactionStore(private val feed: LocalStoreFeed) : ReactionStore {
    private val store: LocalStore get() = feed.store

    private suspend fun <T> io(block: (LocalStore) -> T): T = withContext(Dispatchers.IO) { block(store) }

    // A message arriving makes its waiting reactions visible, so both tables are watched.
    override fun observeChat(chatId: String): Flow<List<ReactionRecord>> =
        combine(feed.watch(LocalStoreTable.REACTIONS) { }, feed.watch(LocalStoreTable.MESSAGES) { }) { _, _ ->
            store.chatMessages(chatId)
                .flatMap { store.reactions(it.id) }
                .sortedBy { it.timestampMs }
                .map { it.record() }
        }

    override suspend fun get(targetMessageId: String, reactorUserId: String): ReactionRecord? = io { s ->
        s.reactions(targetMessageId).firstOrNull { it.reactorUserId == reactorUserId }?.record()
    }

    override suspend fun put(reaction: ReactionRecord) {
        io { s ->
            s.upsertReaction(
                LocalReaction(
                    targetMessageId = reaction.targetMessageId,
                    reactorUserId = reaction.reactorUserId,
                    emoji = reaction.emoji,
                    timestampMs = reaction.timestampMs,
                    receivedAt = reaction.receivedAtMs,
                ),
            )
        }
    }

    override suspend fun delete(targetMessageId: String, reactorUserId: String) {
        io { it.deleteReaction(targetMessageId, reactorUserId) }
    }

    override suspend fun deleteOrphansBefore(cutoffMs: Long) {
        io { s ->
            s.allReactions()
                .filter { r -> r.receivedAt.let { it != null && it <= cutoffMs } && s.message(r.targetMessageId) == null }
                .forEach { s.deleteReaction(it.targetMessageId, it.reactorUserId) }
        }
    }
}

// A row the core holds with no receipt time (iOS's import leaves it so) is never an orphan to sweep.
private fun LocalReaction.record() = ReactionRecord(targetMessageId, reactorUserId, emoji, timestampMs, receivedAt ?: Long.MAX_VALUE)
