package com.construct.messenger.data.repository

import com.construct.messenger.data.model.AuthState
import com.construct.messenger.domain.usecase.RegistrationStep
import kotlinx.coroutines.flow.StateFlow

interface AuthRepository {
    val authState: StateFlow<AuthState>

    /**
     * Establishes identity: registers a new device, or re-authenticates an already
     * registered one (decided internally based on stored device state). [onStep] reports
     * registration progress — see [RegistrationStep]; re-authentication does not use it.
     */
    suspend fun initializeIdentity(username: String?, onStep: (RegistrationStep) -> Unit = {})

    /**
     * Cold-start restore: load private keys, apply the token format guard, re-auth
     * via [com.construct.messenger.domain.usecase.LoginUseCase] if the session is
     * gone, promote the orchestrator, and start [com.construct.messenger.service.MessagingRuntime].
     *
     * @return `true` if an identity is ready ([authState].isInitialized).
     */
    suspend fun restoreSession(): Boolean
}
