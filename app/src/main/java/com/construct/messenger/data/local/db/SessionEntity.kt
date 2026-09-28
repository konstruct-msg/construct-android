package com.construct.messenger.data.local.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/**
 * Opaque secure-store row for CFE session bytes.
 *
 * The Rust core emits typed `CfeAction.SaveToSecureStore(slot, data)` actions.
 * [storeKey] is an Android-only mapping from that slot; [cfeBytes] remains the
 * opaque CFE binary blob (16-byte header + MessagePack). Kotlin never parses it.
 *
 * ByteArray = BLOB; no base64/JSON stringification (CFE binary rule).
 */
@Entity(tableName = "session_state")
data class SessionStateEntity(
    @PrimaryKey val storeKey: String,
    val cfeBytes: ByteArray,
    val updatedAtMs: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SessionStateEntity) return false
        return storeKey == other.storeKey &&
            cfeBytes.contentEquals(other.cfeBytes) &&
            updatedAtMs == other.updatedAtMs
    }

    override fun hashCode(): Int {
        var result = storeKey.hashCode()
        result = 31 * result + cfeBytes.contentHashCode()
        result = 31 * result + updatedAtMs.hashCode()
        return result
    }
}

@Dao
interface SessionStateDao {

    @Upsert
    suspend fun upsert(entry: SessionStateEntity)

    @Query("SELECT * FROM session_state WHERE storeKey = :key")
    suspend fun get(key: String): SessionStateEntity?

    @Query("SELECT * FROM session_state")
    suspend fun getAll(): List<SessionStateEntity>

    @Query("DELETE FROM session_state WHERE storeKey = :key")
    suspend fun delete(key: String)
}
