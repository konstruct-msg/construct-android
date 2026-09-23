package com.construct.messenger.data.auth

import android.util.Log
import com.construct.messenger.data.local.KeystoreManager
import io.grpc.Status
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Observable auth-session state + the 401 → refresh → retry orchestration.
 *
 * **Canon:** `docs/TOKEN_AUTH.md` §3.3/§3.5 and `docs/ANDROID_ONBOARDING.md` §13.5/§13.8
 * (`AuthSessionManager.kt` checklist item). Mirrors iOS session-state handling.
 *
 * Responsibilities:
 * - **Format guard on launch** ([loadSession]) — a cached token that is not
 *   `v4.public.*` is treated as corruption: wipe and require device re-auth.
 * - **Last-resort userId recovery** (§3.4) — if the cached user id was lost,
 *   re-derive it from the token's `sub` claim (the only permitted client-side
 *   token parsing).
 * - **Single retry on UNAUTHENTICATED** ([withAuthRetry]) — any RPC wrapper may
 *   opt in: on 401 the [TokenRefreshCoordinator] is consulted (single-flight),
 *   the call is retried exactly once on success. Permanent refresh failure
 *   (`revoked` / `already used` / no token / no device id) invalidates the
 *   session: tokens are wiped and the caller gets [SessionInvalidatedException]
 *   → fall through to device re-auth (`LoginUseCase`). Transient failures
 *   rethrow the original error so the caller's own retry/backoff applies.
 */
@Singleton
class AuthSessionManager @Inject constructor(
    private val keystoreManager: KeystoreManager,
    private val tokenRefreshCoordinator: TokenRefreshCoordinator,
) {

    enum class AuthSessionState {
        /** Not loaded yet, or no session has ever existed on this device. */
        UNKNOWN,

        /** Tokens loaded, format guard passed. */
        AUTHENTICATED,

        /** Session wiped (format guard or permanent refresh failure) — device re-auth required. */
        INVALIDATED,
    }

    private val _state = MutableStateFlow(AuthSessionState.UNKNOWN)
    val state: StateFlow<AuthSessionState> = _state.asStateFlow()

    /** Cached user id (ServerUserId UUID) — read once at load, NOT re-parsed per call. */
    @Volatile
    var userId: String? = null
        private set

    @Volatile
    var deviceId: String? = null
        private set

    /**
     * Loads the cached session at app start, applying the §13.5 format guard.
     *
     * @return `true` if a usable session exists (state becomes [AuthSessionState.AUTHENTICATED]).
     */
    fun loadSession(): Boolean {
        val accessToken = keystoreManager.getAccessToken()
        userId = keystoreManager.getUserId()
        deviceId = keystoreManager.getDeviceId()

        if (accessToken == null) {
            // Fresh install or logged out — not an invalidation.
            return false
        }

        if (!TokenUtils.isValidFormat(accessToken)) {
            Log.e(TAG, "Unexpected token format in cache — clearing session")
            clearSession()
            return false
        }

        // Last-resort userId recovery (§3.4) — the only hot-path token parsing allowed.
        if (userId == null) {
            userId = TokenUtils.extractUserId(accessToken)?.also { recovered ->
                keystoreManager.saveUserId(recovered)
                Log.i(TAG, "userId recovered from token sub claim")
            }
        }

        _state.value = AuthSessionState.AUTHENTICATED
        return true
    }

    /** Marks the session authenticated after Register/Login persisted fresh tokens. */
    fun onAuthenticated(userId: String, deviceId: String) {
        this.userId = userId
        this.deviceId = deviceId
        _state.value = AuthSessionState.AUTHENTICATED
    }

    /**
     * Runs [block], retrying it exactly once after a successful token refresh
     * if the first attempt fails with gRPC `UNAUTHENTICATED`.
     *
     * @throws SessionInvalidatedException on permanent refresh failure (caller → device re-auth)
     * @throws StatusRuntimeException the original error for non-401 statuses and transient
     *         refresh failures, and any error thrown by the retried call (no retry loops).
     */
    suspend fun <T> withAuthRetry(block: suspend () -> T): T {
        try {
            return block()
        } catch (e: StatusRuntimeException) {
            if (e.status.code != Status.Code.UNAUTHENTICATED) throw e

            Log.w(TAG, "UNAUTHENTICATED — attempting single-flight token refresh")
            return when (val result = tokenRefreshCoordinator.refreshIfPossible()) {
                is TokenRefreshResult.Success -> {
                    Log.i(TAG, "token refreshed — retrying call once")
                    block()
                }
                is TokenRefreshResult.Failure -> {
                    if (result.error.isPermanent) {
                        Log.e(TAG, "permanent refresh failure: ${result.error} — invalidating session")
                        clearSession()
                        throw SessionInvalidatedException(result.error)
                    }
                    // Transient (network) — the caller's retry/backoff owns this, not us.
                    throw e
                }
            }
        }
    }

    /** Wipes tokens and marks the session invalidated. Device id and private keys are kept. */
    fun clearSession() {
        keystoreManager.clearTokens()
        userId = null
        _state.value = AuthSessionState.INVALIDATED
    }

    private companion object {
        const val TAG = "AuthSessionManager"
    }
}

/**
 * Thrown by [AuthSessionManager.withAuthRetry] when the session cannot be
 * recovered — the caller must fall through to device re-auth (PoW + device
 * signature via `LoginUseCase`); there is no login/logout in the messenger.
 */
class SessionInvalidatedException(
    val reason: TokenRefreshError,
) : Exception("Session invalidated: $reason")
