package com.construct.messenger.data.repository

import android.content.Context
import android.content.Intent
import com.construct.messenger.diagnostics.Log
import androidx.core.content.ContextCompat
import com.construct.messenger.crypto.CryptoManager
import com.construct.messenger.data.auth.AuthSessionManager
import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.data.model.AuthState
import com.construct.messenger.data.api.GrpcClient
import com.construct.messenger.domain.usecase.LoginUseCase
import com.construct.messenger.domain.usecase.RecoverAccountUseCase
import com.construct.messenger.domain.usecase.RegisterUseCase
import com.construct.messenger.domain.usecase.RegistrationStep
import com.construct.messenger.domain.usecase.restoreOneTimePrekeys
import com.construct.messenger.service.MessagingRuntime
import com.construct.messenger.service.MessagingForegroundService
import dagger.hilt.android.qualifiers.ApplicationContext
import shared.proto.services.v1.AuthServiceOuterClass.LogoutRequest
import shared.proto.services.v1.UserServiceOuterClass.DeleteAccountRequest
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    @param:ApplicationContext private val context: Context,
    private val registerUseCase: RegisterUseCase,
    private val loginUseCase: LoginUseCase,
    private val recoverAccountUseCase: RecoverAccountUseCase,
    private val keystoreManager: KeystoreManager,
    private val cryptoManager: CryptoManager,
    private val authSession: AuthSessionManager,
    private val messagingRuntime: MessagingRuntime,
    private val grpcClient: GrpcClient,
) : AuthRepository {

    private val mutableAuthState = MutableStateFlow(
        AuthState(isInitialized = keystoreManager.getAccessToken() != null),
    )
    override val authState: StateFlow<AuthState> = mutableAuthState.asStateFlow()

    /**
     * Runs to the end even if the caller is cancelled. [RegisterUseCase] reports
     * [RegistrationStep.Complete] before its prekey upload finishes (iOS canon), so the user can
     * leave onboarding — clearing the caller's viewModelScope — while this is still running.
     * Cancelled there, the device was registered with no one-time prekeys and no
     * [MessagingForegroundService] until the next cold start (seen on a device, 2026-09-24).
     *
     * Off the main thread: key generation and the proof-of-work nonce search are blocking core
     * calls, and the caller is a viewModelScope. On the main thread they froze the screen for
     * ~26 s on a Redmi — no frame drawn, the progress never moved (2026-09-30).
     */
    override suspend fun initializeIdentity(
        username: String?,
        onStep: (RegistrationStep) -> Unit,
    ) = withContext(NonCancellable + Dispatchers.Default) {
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

        val userId = keystoreManager.getUserId()
        if (userId != null) {
            authSession.onAuthenticated(userId, resolvedDeviceId)
        }
        mutableAuthState.value = AuthState(
            isInitialized = true,
            deviceId = resolvedDeviceId,
            username = resolvedUsername,
        )
        startMessagingService()
    }

    /** Runs to the end even if the caller is cancelled — same reason as [initializeIdentity]. */
    override suspend fun recoverAccount(identifier: String, phrase: String) =
        withContext(NonCancellable + Dispatchers.Default) {
            val deviceId = recoverAccountUseCase(identifier, phrase)
            keystoreManager.getUserId()?.let { authSession.onAuthenticated(it, deviceId) }
            mutableAuthState.value = AuthState(isInitialized = true, deviceId = deviceId, username = null)
            startMessagingService()
        }

    override suspend fun restoreSession(): Boolean {
        val keys = keystoreManager.getPrivateKeys()
        val deviceId = keystoreManager.getDeviceId()
        if (keys == null || deviceId == null) {
            mutableAuthState.value = AuthState(isInitialized = false)
            return false
        }

        try {
            val sessionOk = authSession.loadSession()
            val userId = authSession.userId ?: keystoreManager.getUserId()
            if (sessionOk && userId != null) {
                ensureOrchestrator(keys)
            } else {
                loginUseCase(deviceId, keys)
                val loggedInUserId = keystoreManager.getUserId()
                    ?: error("LoginUseCase succeeded without persisting userId")
                authSession.onAuthenticated(loggedInUserId, deviceId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "restoreSession failed", e)
            mutableAuthState.value = AuthState(isInitialized = false)
            return false
        }

        mutableAuthState.value = AuthState(
            isInitialized = true,
            deviceId = deviceId,
            username = null,
        )
        startMessagingService()
        return true
    }

    /**
     * Tokens were valid; the process is new so the UniFFI core is empty.
     * The account id is needed by the app/session layer; CryptoManager derives the
     * local CryptoDeviceId and passes only that device id into construct-core.
     */
    private fun ensureOrchestrator(savedPrivateKeys: ByteArray) {
        if (cryptoManager.isMessagingReady) return
        val userId = authSession.userId ?: keystoreManager.getUserId()
            ?: error("session loaded but userId is missing")
        cryptoManager.loadOrCreate(savedPrivateKeys)
        restoreOneTimePrekeys(cryptoManager, keystoreManager)
        cryptoManager.setLocalUserId(userId, keystoreManager.getKyberPrekeys())
    }

    override suspend fun logout(allDevices: Boolean) {
        // Nothing is announced to contacts: the device leaves the account's directory. Until
        // 2026-09-27 an END_SESSION went to every contact here.
        val token = keystoreManager.getAccessToken()
        if (token != null) {
            runCatching {
                grpcClient.auth.logout(
                    LogoutRequest.newBuilder().setAccessToken(token).setAllDevices(allDevices).build(),
                )
            }.onFailure { Log.w(TAG, "Logout RPC failed", it) }
        }
        messagingRuntime.stop()
        context.stopService(Intent(context, MessagingForegroundService::class.java))
        authSession.clearSession()
        keystoreManager.clearTokens()
        cryptoManager.close()
        mutableAuthState.value = AuthState()
    }

    override suspend fun deleteAccount() {
        val response = grpcClient.user.deleteAccount(
            DeleteAccountRequest.newBuilder().setConfirmation("DELETE").setReason("user_requested").build(),
        )
        check(response.success) { response.message.ifEmpty { "the server refused" } }
        messagingRuntime.stop()
        context.stopService(Intent(context, MessagingForegroundService::class.java))
    }

    private companion object {
        const val TAG = "AuthRepository"
    }

    private fun startMessagingService() {
        ContextCompat.startForegroundService(
            context,
            Intent(context, MessagingForegroundService::class.java),
        )
    }
}
