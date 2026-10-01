package com.construct.messenger.data.local.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * One reactor's emoji on one message. **Canon:** iOS Core Data `Reaction`.
 *
 * Never a message row, and keyed by the target id rather than joined to it: a reaction may land
 * before its message (an orphan) and waits here up to `ReactionRules.ORPHAN_TTL_MS`. Both ids are
 * stored lower-cased — message ids are compared the way iOS compares them (`==[c]`).
 */
@Entity(tableName = "reactions", primaryKeys = ["targetMessageId", "reactorUserId"])
data class ReactionEntity(
    val targetMessageId: String,
    val reactorUserId: String,
    val emoji: String,
    /** The last-write-wins clock: the reactor's `timestamp_ms`, or receive time for an old peer. */
    val timestampMs: Long,
    /** When this phone stored it — what the orphan sweep measures. */
    val receivedAtMs: Long,
)

@Dao
interface ReactionDao {

    @Query("SELECT * FROM reactions WHERE targetMessageId = :target AND reactorUserId = :reactor LIMIT 1")
    suspend fun get(target: String, reactor: String): ReactionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: ReactionEntity)

    @Query("DELETE FROM reactions WHERE targetMessageId = :target AND reactorUserId = :reactor")
    suspend fun delete(target: String, reactor: String)

    /** Every reaction on a message of [chatId], oldest first — the order the badges line up in. */
    @Query(
        "SELECT r.* FROM reactions r JOIN messages m ON lower(m.id) = r.targetMessageId " +
            "WHERE m.chatId = :chatId ORDER BY r.timestampMs ASC",
    )
    fun observeChat(chatId: String): Flow<List<ReactionEntity>>

    /** Reactions stored before [cutoffMs] whose message is not here. */
    @Query(
        "DELETE FROM reactions WHERE receivedAtMs <= :cutoffMs AND targetMessageId NOT IN " +
            "(SELECT lower(id) FROM messages)",
    )
    suspend fun deleteOrphansBefore(cutoffMs: Long)

    @Query("DELETE FROM reactions WHERE targetMessageId = :target")
    suspend fun deleteFor(target: String)
}
