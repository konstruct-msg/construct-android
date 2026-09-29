package com.construct.messenger.data.mock

import com.construct.messenger.data.model.AuthState
import com.construct.messenger.data.repository.AuthRepository
import com.construct.messenger.domain.usecase.RegistrationStep
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

    override suspend fun initializeIdentity(username: String?, onStep: (RegistrationStep) -> Unit) {
        onStep(RegistrationStep.GeneratingKeys)
        delay(250)
        onStep(RegistrationStep.Complete)
        mutableAuthState.value = AuthState(isInitialized = true)
    }

    override suspend fun restoreSession(): Boolean = mutableAuthState.value.isInitialized
    override suspend fun recoverAccount(identifier: String, phrase: String) = Unit

    override suspend fun deleteAccount() {
        mutableAuthState.value = AuthState()
    }

    override suspend fun logout(allDevices: Boolean) {
        mutableAuthState.value = AuthState()
    }
}
