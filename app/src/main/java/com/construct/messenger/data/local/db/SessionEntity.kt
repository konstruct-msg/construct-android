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
 * The Rust core emits `CfeAction.SaveSessionToSecureStore(key, data)`; [storeKey]
 * is that opaque key and [cfeBytes] is the CFE binary blob (16-byte header +
 * MessagePack, see `docs/FFI_BINARY_FORMAT.md`). Kotlin never parses the blob —
 * it round-trips back into Rust via `importSessionBytes` untouched.
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

/**
 * Per-peer session establishment timestamp.
 *
 * **Storm hardening, invariant #6** (`docs/ANDROID_ONBOARDING.md` §12 —
 * "Stale END_SESSION filter"): an END_SESSION whose timestamp pre-dates the
 * current session's [establishedAtMs] is stale — ACK and drop. Must survive
 * process restarts and be hydrated for CFE-restored sessions BEFORE any queued
 * control message is processed.
 */
@Entity(tableName = "session_meta")
data class SessionMetaEntity(
    @PrimaryKey val contactId: String,
    val establishedAtMs: Long,
)

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

@Dao
interface SessionMetaDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setEstablishedAt(entry: SessionMetaEntity)

    @Query("SELECT establishedAtMs FROM session_meta WHERE contactId = :contactId")
    suspend fun getEstablishedAt(contactId: String): Long?

    @Query("SELECT * FROM session_meta")
    suspend fun getAll(): List<SessionMetaEntity>

    @Query("DELETE FROM session_meta WHERE contactId = :contactId")
    suspend fun delete(contactId: String)
}
