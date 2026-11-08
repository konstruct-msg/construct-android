package com.construct.messenger.data.local

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** [AtRiskSessionStore] in memory, for tests. */
class InMemoryAtRiskSessionStore(initial: Map<String, String> = emptyMap()) : AtRiskSessionStore {
    val marked = MutableStateFlow(initial)

    override fun observe(): Flow<Map<String, String>> = marked

    override suspend fun mark(deviceId: String, accountId: String) {
        marked.value = marked.value + (deviceId to accountId)
    }

    override suspend fun clear(deviceId: String) {
        marked.value = marked.value - deviceId
    }
}
