package com.construct.messenger.data.local.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * One decrypted KNST chunk of a message not yet complete. Backs
 * [com.construct.messenger.service.ChunkReassembler]. Keyed by the sending account as well as the
 * frame's id, so one sender's frames can never fill in another's message.
 *
 * Decrypted bytes, in the same database as the decrypted messages they become.
 */
@Entity(tableName = "pending_chunks", primaryKeys = ["senderId", "messageId", "chunkIndex"])
data class PendingChunkEntity(
    val senderId: String,
    val messageId: String,
    val chunkIndex: Int,
    val totalChunks: Int,
    val plaintextLength: Int,
    val contentType: Int,
    val payload: ByteArray,
    val receivedAtMs: Long,
)

@Dao
interface PendingChunkDao {

    /** A redelivered chunk keeps the copy already held. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(chunk: PendingChunkEntity)

    @Query("SELECT * FROM pending_chunks WHERE senderId = :senderId AND messageId = :messageId ORDER BY chunkIndex")
    suspend fun chunks(senderId: String, messageId: String): List<PendingChunkEntity>

    @Query("SELECT COUNT(*) FROM pending_chunks WHERE senderId = :senderId AND messageId = :messageId")
    suspend fun count(senderId: String, messageId: String): Int

    @Query("DELETE FROM pending_chunks WHERE senderId = :senderId AND messageId = :messageId")
    suspend fun delete(senderId: String, messageId: String)

    @Query("DELETE FROM pending_chunks WHERE receivedAtMs < :thresholdMs")
    suspend fun pruneOlderThan(thresholdMs: Long): Int
}
