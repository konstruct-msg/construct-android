package com.construct.messenger.data.local

import com.construct.messenger.calls.CallRecordStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import uniffi.construct_core.LocalCall
import uniffi.construct_core.LocalStore
import uniffi.construct_core.LocalStoreTable

/**
 * [CallStore] on the core's encrypted `LocalStore` (TODO 136) — held to the same
 * [CallStoreContract] as [RoomCallStore]. The store once [RoomImport] has moved
 * Room's rows ([LocalStores]).
 *
 * The row is iOS's `CallRecord`: direction `0` outgoing, `1` incoming; status `0` completed,
 * `1` missed, `2` declined, `3` failed — [CallRecordStatus] in the same order.
 */
class CoreCallStore(private val feed: LocalStoreFeed) : CallStore {
    private val store: LocalStore get() = feed.store

    private suspend fun <T> io(block: (LocalStore) -> T): T = withContext(Dispatchers.IO) { block(store) }

    override fun observeRecent(): Flow<List<CallRecord>> =
        feed.watch(LocalStoreTable.CALLS) { s -> s.calls(RECENT).map { it.record() } }

    override suspend fun put(call: CallRecord) = io { it.upsertCall(call.local()) }

    override suspend fun delete(id: String) {
        io { it.deleteCall(id) }
    }

    override suspend fun deleteAll() {
        io { it.deleteAllCalls() }
    }
}

// `CallStore.RECENT.toUInt()` crashes the compiler's constant folding (Kotlin 2.x); the same number as a literal.
private val RECENT: UInt = 200u.also { check(it.toInt() == CallStore.RECENT) }

private fun CallRecord.local() = LocalCall(
    id = id,
    peerUserId = peerUserId,
    peerName = peerName,
    direction = if (incoming) 1 else 0,
    status = status.ordinal.toShort(),
    startedAt = startedAtMs,
    endedAt = endedAtMs,
    durationSeconds = durationSeconds,
)

private fun LocalCall.record() = CallRecord(
    id = id,
    peerUserId = peerUserId,
    peerName = peerName,
    incoming = direction.toInt() == 1,
    // A status this build does not know reads as a completed call, as iOS reads an unknown raw value.
    status = CallRecordStatus.entries.getOrElse(status.toInt()) { CallRecordStatus.COMPLETED },
    // A row from history import may carry no times: iOS leaves them unset, Android shows the start.
    startedAtMs = startedAt ?: 0,
    endedAtMs = endedAt ?: startedAt ?: 0,
    durationSeconds = durationSeconds,
)
