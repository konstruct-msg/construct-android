package com.construct.messenger.data.local.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * A message the core asked us to send again to one device, not sent yet. Backs
 * [com.construct.messenger.service.PendingResends]. [messageId] is the id the reader named — for
 * a sealed copy the server's; [accountId] is the device's account.
 */
@Entity(tableName = "pending_resends", primaryKeys = ["messageId", "deviceId"])
data class PendingResendEntity(
    val messageId: String,
    val deviceId: String,
    val accountId: String,
    val createdAtMs: Long,
    val attempts: Int = 0,
)

@Dao
interface PendingResendDao {

    /** A second error for the same copy keeps the first row, its age and its attempts. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: PendingResendEntity)

    @Query("SELECT * FROM pending_resends ORDER BY createdAtMs")
    suspend fun all(): List<PendingResendEntity>

    @Query("DELETE FROM pending_resends WHERE messageId = :messageId AND deviceId = :deviceId")
    suspend fun delete(messageId: String, deviceId: String)

    @Query("UPDATE pending_resends SET attempts = attempts + 1 WHERE messageId = :messageId AND deviceId = :deviceId")
    suspend fun countAttempt(messageId: String, deviceId: String)

    @Query("DELETE FROM pending_resends WHERE createdAtMs < :thresholdMs")
    suspend fun pruneOlderThan(thresholdMs: Long): Int
}
