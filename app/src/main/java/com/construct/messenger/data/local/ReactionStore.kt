package com.construct.messenger.data.local

import com.construct.messenger.data.local.db.ReactionDao
import com.construct.messenger.data.local.db.ReactionEntity
import com.construct.messenger.util.ReactionRules
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Applies [ReactionRules] decisions to Room. **Canon:** iOS `ReactionStore`.
 *
 * Every write sweeps orphans — reactions whose message never arrived — once they are older than
 * `ReactionRules.ORPHAN_TTL_MS`.
 */
@Singleton
class ReactionStore @Inject constructor(
    private val dao: ReactionDao,
) {
    suspend fun current(targetMessageId: String, reactorUserId: String): ReactionRules.Row? =
        dao.get(targetMessageId.lowercase(), reactorUserId.lowercase())?.let { ReactionRules.Row(it.emoji, it.timestampMs) }

    suspend fun applyIncoming(
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
            is ReactionRules.Decision.Set ->
                dao.upsert(ReactionEntity(target, reactor, decision.emoji, decision.timestampMs, nowMs))
            ReactionRules.Decision.Clear -> dao.delete(target, reactor)
            ReactionRules.Decision.KeepExisting, ReactionRules.Decision.DropInvalid -> Unit
        }
        dao.deleteOrphansBefore(nowMs - ReactionRules.ORPHAN_TTL_MS)
        return decision
    }

    /**
     * Put back what a tap replaced when the wire refused it. Not last-write-wins: the tap being
     * undone carries a later clock than the row it replaced.
     */
    suspend fun restoreLocal(targetMessageId: String, reactorUserId: String, previous: ReactionRules.Row?, nowMs: Long) {
        val target = targetMessageId.lowercase()
        val reactor = reactorUserId.lowercase()
        if (previous != null) {
            dao.upsert(ReactionEntity(target, reactor, previous.emoji, previous.timestampMs, nowMs))
        } else {
            dao.delete(target, reactor)
        }
    }
}
