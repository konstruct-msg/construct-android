package com.construct.messenger.data.repository

import com.construct.messenger.calls.CallHistoryPort
import com.construct.messenger.calls.CallRecordStatus
import com.construct.messenger.calls.CallSession
import com.construct.messenger.data.local.CallRecord
import com.construct.messenger.data.local.CallStore
import com.construct.messenger.data.local.ContactStore
import com.construct.messenger.data.local.resolvedName
import com.construct.messenger.data.model.CallHistoryEntry
import com.construct.messenger.diagnostics.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** The Calls tab's list. Only this device's calls: the history is not synced, as on iOS. */
interface CallHistoryRepository {
    val recent: Flow<List<CallHistoryEntry>>
    suspend fun delete(id: String)
    suspend fun clear()
}

/**
 * **Canon:** iOS `CallHistoryService` — written when a call ends, from [CallHistoryPort], which
 * [com.construct.messenger.calls.CallManager] calls without waiting.
 */
@Singleton
class CallHistoryRepositoryImpl @Inject constructor(
    private val calls: CallStore,
    private val users: ContactStore,
) : CallHistoryRepository, CallHistoryPort {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val recent: Flow<List<CallHistoryEntry>> =
        combine(calls.observeRecent(), users.observeAll()) { rows, people ->
            val byId = people.associateBy { it.id }
            rows.map { row ->
                val user = byId[row.peerUserId]
                CallHistoryEntry(
                    id = row.id,
                    peerUserId = row.peerUserId,
                    peerName = if (user != null) user.resolvedName(row.peerUserId) else row.peerName,
                    peerAvatar = user?.avatarData,
                    incoming = row.incoming,
                    status = CallHistoryEntry.Status.valueOf(row.status.name),
                    startedAtMs = row.startedAtMs,
                    durationSeconds = row.durationSeconds,
                )
            }
        }

    override fun record(
        session: CallSession,
        status: CallRecordStatus,
        startedAtMs: Long,
        endedAtMs: Long,
        durationSeconds: Int,
    ) {
        scope.launch {
            runCatching {
                calls.put(
                    CallRecord(
                        id = session.id,
                        peerUserId = session.peerUserId,
                        peerName = session.peerName,
                        incoming = session.isIncoming,
                        status = status,
                        startedAtMs = startedAtMs,
                        endedAtMs = endedAtMs,
                        durationSeconds = durationSeconds,
                    ),
                )
            }.onFailure { Log.w(TAG, "call not recorded", it) }
        }
    }

    override suspend fun delete(id: String) = calls.delete(id)

    override suspend fun clear() = calls.deleteAll()

    private companion object {
        const val TAG = "CallHistory"
    }
}
