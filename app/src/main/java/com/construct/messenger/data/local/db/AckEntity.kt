package com.construct.messenger.data.local.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Durable record of an already-processed incoming message.
 *
 * Backs `PersistentAckStore` (the Android port of iOS `PersistentACKStore`):
 * after a restart the Rust CFE in-memory ACK cache is empty, so the DB is the
 * only thing preventing re-processing of messages re-delivered by the server
 * offline queue on every reconnect (cf. the END_SESSION storm root cause,
 * `construct-docs/sessions/2026-07-16-end-session-storm-fix.md`).
 */
@Entity(tableName = "acked_messages")
data class AckedMessageEntity(
    @PrimaryKey val messageId: String,
    val senderId: String,
    val processedAtMs: Long,
)

@Dao
interface AckDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: AckedMessageEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM acked_messages WHERE messageId = :messageId)")
    suspend fun exists(messageId: String): Boolean

    /** All known ids — used to hydrate the in-memory cache at startup. */
    @Query("SELECT messageId FROM acked_messages")
    suspend fun getAllIds(): List<String>

    /** Drops old rows; returns the number deleted. */
    @Query("DELETE FROM acked_messages WHERE processedAtMs < :thresholdMs")
    suspend fun pruneOlderThan(thresholdMs: Long): Int
}
