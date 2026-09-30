package com.construct.messenger.data.local.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * A server-assigned id of one of our sealed copies, and the message it is a copy of. Backs
 * [com.construct.messenger.service.ServerMessageIds]; both ids lowercased.
 */
@Entity(tableName = "server_message_ids")
data class ServerMessageIdEntity(
    @PrimaryKey val serverId: String,
    val localId: String,
    val recordedAtMs: Long,
)

@Dao
interface ServerMessageIdDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: ServerMessageIdEntity)

    @Query("SELECT localId FROM server_message_ids WHERE serverId = :serverId")
    suspend fun localId(serverId: String): String?

    @Query("DELETE FROM server_message_ids WHERE recordedAtMs < :thresholdMs")
    suspend fun pruneOlderThan(thresholdMs: Long): Int
}
