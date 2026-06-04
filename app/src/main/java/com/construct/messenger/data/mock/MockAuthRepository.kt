package com.construct.messenger.data.mock

import com.construct.messenger.data.model.AuthState
import com.construct.messenger.data.repository.AuthRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MockAuthRepository @Inject constructor() : AuthRepository {
    private val mutableAuthState = MutableStateFlow(AuthState())

    override val authState: StateFlow<AuthState> = mutableAuthState.asStateFlow()

    override suspend fun initializeIdentity() {
        delay(250)
        mutableAuthState.value = AuthState(isInitialized = true)
    }
}
