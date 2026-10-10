package com.construct.messenger.data.local

import com.construct.messenger.calls.CallRecordStatus
import com.construct.messenger.data.local.db.CallRecordDao
import com.construct.messenger.data.local.db.CallRecordEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * [CallStore] on Room's `call_records` table — until the core's `LocalStore` takes it (TODO 136).
 * Built by `DatabaseModule`, which provides no `CallRecordDao`: nothing else can reach the table.
 */
class RoomCallStore(private val dao: CallRecordDao) : CallStore {
    override fun observeRecent(): Flow<List<CallRecord>> = dao.observeRecent().map { rows -> rows.map { it.record() } }

    override suspend fun put(call: CallRecord) = dao.insert(
        CallRecordEntity(
            id = call.id,
            peerUserId = call.peerUserId,
            peerName = call.peerName,
            incoming = call.incoming,
            status = call.status.name,
            startedAtMs = call.startedAtMs,
            endedAtMs = call.endedAtMs,
            durationSeconds = call.durationSeconds,
        ),
    )

    override suspend fun delete(id: String) = dao.delete(id)

    override suspend fun deleteAll() = dao.deleteAll()
}

private fun CallRecordEntity.record() = CallRecord(
    id = id,
    peerUserId = peerUserId,
    peerName = peerName,
    incoming = incoming,
    // A name this build does not know reads as a completed call, as iOS reads an unknown raw value.
    status = runCatching { CallRecordStatus.valueOf(status) }.getOrDefault(CallRecordStatus.COMPLETED),
    startedAtMs = startedAtMs,
    endedAtMs = endedAtMs,
    durationSeconds = durationSeconds,
)
