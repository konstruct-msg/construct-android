package com.construct.messenger.data.local

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** [CallStore] in a map, for tests; [CallStoreContract] holds it to Room's behaviour. */
internal class FakeCallStore : CallStore {
    val rows = linkedMapOf<String, CallRecord>()

    override fun observeRecent(): Flow<List<CallRecord>> = flow {
        emit(rows.values.sortedByDescending { it.startedAtMs }.take(CallStore.RECENT))
    }

    override suspend fun put(call: CallRecord) {
        rows[call.id] = call
    }

    override suspend fun delete(id: String) {
        rows.remove(id)
    }

    override suspend fun deleteAll() = rows.clear()
}
