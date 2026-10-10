package com.construct.messenger.data.local

import com.construct.messenger.calls.CallRecordStatus
import kotlinx.coroutines.flow.Flow

/**
 * One ended call. **Canon:** iOS `CTCallRecord`. [peerName] is the name at the time of the call,
 * for a peer no longer in the contact list.
 */
data class CallRecord(
    /** The call id — one row per call. */
    val id: String,
    val peerUserId: String,
    val peerName: String,
    val incoming: Boolean,
    val status: CallRecordStatus,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val durationSeconds: Int,
)

/**
 * This device's call history (TODO 136): Room's table today ([RoomCallStore]); the core's
 * `LocalStore` once it can delete a call. [CallStoreContract] holds every store to one behaviour.
 */
interface CallStore {
    /** iOS `CallHistoryView.loadRecords`: the newest 200, newest first. */
    fun observeRecent(): Flow<List<CallRecord>>

    /** Writes [call] over any row with its id. */
    suspend fun put(call: CallRecord)

    suspend fun delete(id: String)

    suspend fun deleteAll()

    companion object {
        const val RECENT = 200
    }
}
