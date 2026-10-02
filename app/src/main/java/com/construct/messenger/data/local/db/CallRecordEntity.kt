package com.construct.messenger.data.local.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * One ended call. **Canon:** iOS `CTCallRecord` (Core Data `CallRecord`). [peerName] is the name
 * at the time of the call, for a peer no longer in the contact list.
 */
@Entity(tableName = "call_records")
data class CallRecordEntity(
    /** The call id — one row per call. */
    @PrimaryKey val id: String,
    val peerUserId: String,
    val peerName: String,
    val incoming: Boolean,
    /** [com.construct.messenger.calls.CallRecordStatus] by name. */
    val status: String,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val durationSeconds: Int,
)

@Dao
interface CallRecordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(row: CallRecordEntity)

    /** iOS `CallHistoryView.loadRecords`: the newest 200. */
    @Query("SELECT * FROM call_records ORDER BY startedAtMs DESC LIMIT 200")
    fun observeRecent(): Flow<List<CallRecordEntity>>

    @Query("DELETE FROM call_records WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM call_records")
    suspend fun deleteAll()
}
