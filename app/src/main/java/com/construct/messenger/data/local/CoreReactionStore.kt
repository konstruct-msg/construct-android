package com.construct.messenger.data.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
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
 * A chat's reactions are one read (`reactions_in_chat`), and the sweep is the core's
 * `expire_reactions`, which forgets only orphans — iOS's and Android's rule (both core 0.39.0).
 */
class CoreReactionStore(private val feed: LocalStoreFeed) : ReactionStore {
    private val store: LocalStore get() = feed.store

    private suspend fun <T> io(block: (LocalStore) -> T): T = withContext(Dispatchers.IO) { block(store) }

    // A message arriving makes its waiting reactions visible, so both tables are watched.
    override fun observeChat(chatId: String): Flow<List<ReactionRecord>> =
        combine(feed.watch(LocalStoreTable.REACTIONS) { }, feed.watch(LocalStoreTable.MESSAGES) { }) { _, _ ->
            // The core lists them by message, then time; the badges line up by time alone.
            store.reactionsInChat(chatId)
                .sortedBy { it.timestampMs }
                .map { it.record() }
        }.flowOn(Dispatchers.IO)

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
        io { it.expireReactions(cutoffMs) }
    }
}

// A row the core holds with no receipt time (iOS's import leaves it so) is never an orphan to sweep.
private fun LocalReaction.record() = ReactionRecord(targetMessageId, reactorUserId, emoji, timestampMs, receivedAt ?: Long.MAX_VALUE)
