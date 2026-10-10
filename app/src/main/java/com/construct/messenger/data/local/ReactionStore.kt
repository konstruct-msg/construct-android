package com.construct.messenger.data.local

import com.construct.messenger.util.ReactionRules
import kotlinx.coroutines.flow.Flow

/**
 * One reactor's emoji on one message. **Canon:** iOS Core Data `Reaction`.
 *
 * Keyed by the target id, never joined to it: a reaction may land before its message (an orphan)
 * and waits up to `ReactionRules.ORPHAN_TTL_MS`. Both ids are lowercase — message ids are
 * compared the way iOS compares them (`==[c]`), and the core compares exactly.
 */
data class ReactionRecord(
    val targetMessageId: String,
    val reactorUserId: String,
    val emoji: String,
    /** The last-write-wins clock: the reactor's `timestamp_ms`, or receive time for an old peer. */
    val timestampMs: Long,
    /** When this phone stored it — what the orphan sweep measures. */
    val receivedAtMs: Long,
)

/**
 * Where reactions are kept (TODO 136): Room's table today ([RoomReactionStore]), the core's
 * `LocalStore` next ([CoreReactionStore]); [ReactionStoreContract] holds both to one behaviour.
 * The rules — who wins, what is dropped — are [applyIncoming], above any store.
 */
interface ReactionStore {
    /** Every reaction on a message of [chatId], oldest first — the order the badges line up in. */
    fun observeChat(chatId: String): Flow<List<ReactionRecord>>

    suspend fun get(targetMessageId: String, reactorUserId: String): ReactionRecord?

    /** Writes [reaction] over the reactor's previous one on that message. */
    suspend fun put(reaction: ReactionRecord)

    suspend fun delete(targetMessageId: String, reactorUserId: String)

    /** Forgets reactions stored at or before [cutoffMs] whose message is not here. */
    suspend fun deleteOrphansBefore(cutoffMs: Long)
}

suspend fun ReactionStore.current(targetMessageId: String, reactorUserId: String): ReactionRules.Row? =
    get(targetMessageId.lowercase(), reactorUserId.lowercase())?.let { ReactionRules.Row(it.emoji, it.timestampMs) }

/**
 * Applies [ReactionRules] to the store. **Canon:** iOS `ReactionStore`. Every write sweeps
 * orphans — reactions whose message never arrived — once they are older than
 * `ReactionRules.ORPHAN_TTL_MS`.
 */
suspend fun ReactionStore.applyIncoming(
    targetMessageId: String,
    reactorUserId: String,
    actionRawValue: Int,
    emoji: String,
    payloadTimestampMs: Long,
    fallbackTimestampMs: Long,
    nowMs: Long,
    props: ReactionRules.EmojiProperties = ReactionRules.EmojiProperties.Icu,
): ReactionRules.Decision {
    val target = targetMessageId.trim().lowercase()
    val reactor = reactorUserId.lowercase()
    val decision = ReactionRules.apply(
        existing = current(target, reactor),
        incoming = ReactionRules.incoming(actionRawValue, emoji, props),
        timestampMs = ReactionRules.normalizeTimestamp(payloadTimestampMs, fallbackTimestampMs),
        targetMessageId = target,
    )
    when (decision) {
        is ReactionRules.Decision.Set -> put(ReactionRecord(target, reactor, decision.emoji, decision.timestampMs, nowMs))
        ReactionRules.Decision.Clear -> delete(target, reactor)
        ReactionRules.Decision.KeepExisting, ReactionRules.Decision.DropInvalid -> Unit
    }
    deleteOrphansBefore(nowMs - ReactionRules.ORPHAN_TTL_MS)
    return decision
}

/**
 * Put back what a tap replaced when the wire refused it. Not last-write-wins: the tap being
 * undone carries a later clock than the row it replaced.
 */
suspend fun ReactionStore.restoreLocal(targetMessageId: String, reactorUserId: String, previous: ReactionRules.Row?, nowMs: Long) {
    val target = targetMessageId.lowercase()
    val reactor = reactorUserId.lowercase()
    if (previous != null) {
        put(ReactionRecord(target, reactor, previous.emoji, previous.timestampMs, nowMs))
    } else {
        delete(target, reactor)
    }
}
