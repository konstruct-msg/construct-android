package com.construct.messenger.data.mock

import com.construct.messenger.data.local.OrientationStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-memory [OrientationStore] for unit tests (mirrors [MockAuthRepository]). */
class MockOrientationStore(initiallyCompleted: Boolean = false) : OrientationStore {
    private val state = MutableStateFlow(initiallyCompleted)

    override val completed: Flow<Boolean> = state.asStateFlow()

    override suspend fun setCompleted() {
        state.value = true
    }
}
