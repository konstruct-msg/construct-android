package com.construct.messenger.data.repository

import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.data.model.AuthState
import com.construct.messenger.domain.usecase.LoginUseCase
import com.construct.messenger.domain.usecase.RegisterUseCase
import com.construct.messenger.domain.usecase.RegistrationStep
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real [AuthRepository]: registers a new device via [RegisterUseCase], or restores an
 * existing identity and re-authenticates via [LoginUseCase] — decided by whether
 * [KeystoreManager] already has a device id + private keys saved.
 */
@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val registerUseCase: RegisterUseCase,
    private val loginUseCase: LoginUseCase,
    private val keystoreManager: KeystoreManager,
) : AuthRepository {

    private val mutableAuthState = MutableStateFlow(
        AuthState(isInitialized = keystoreManager.getAccessToken() != null),
    )
    override val authState: StateFlow<AuthState> = mutableAuthState.asStateFlow()

    override suspend fun initializeIdentity(username: String?, onStep: (RegistrationStep) -> Unit) {
        val existingDeviceId = keystoreManager.getDeviceId()
        val savedPrivateKeys = keystoreManager.getPrivateKeys()

        val resolvedDeviceId: String
        val resolvedUsername: String?
        if (existingDeviceId != null && savedPrivateKeys != null) {
            loginUseCase(existingDeviceId, savedPrivateKeys)
            resolvedDeviceId = existingDeviceId
            resolvedUsername = null
        } else {
            val result = registerUseCase(username, onStep)
            resolvedDeviceId = result.deviceId
            resolvedUsername = username
        }

        mutableAuthState.value = AuthState(
            isInitialized = true,
            deviceId = resolvedDeviceId,
            username = resolvedUsername,
        )
    }
}
