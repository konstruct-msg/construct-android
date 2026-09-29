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

    /**
     * Sign in to an existing account with its recovery phrase — this device gets a fresh identity
     * and the account's other devices are signed out. Throws on refusal
     * ([com.construct.messenger.domain.usecase.RecoverRefused]) or the server's error.
     */
    suspend fun recoverAccount(identifier: String, phrase: String)

    /**
     * `Logout` RPC, drop tokens, stop the runtime. Nothing is sent to contacts. [allDevices] asks
     * the server to end every session of the account, this one included.
     */
    suspend fun logout(allDevices: Boolean = false)

    /**
     * Asks the server to delete the account (`UserService.DeleteAccount`, confirmation "DELETE",
     * as iOS sends). Returns normally only on the server's yes; throws otherwise. The caller erases
     * the device afterwards — this does not.
     */
    suspend fun deleteAccount()
}
