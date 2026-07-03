package com.construct.messenger.data.auth

import com.construct.messenger.data.api.GrpcClient
import com.construct.messenger.data.local.KeystoreManager
import io.grpc.Status
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import shared.proto.services.v1.AuthServiceOuterClass.RefreshTokenRequest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result of a token refresh attempt.
 * Sealed — either [Success] with the new token set or [Failure] with a classified error.
 */
sealed interface TokenRefreshResult {
    data class Success(
        val accessToken: String,
        /** Rotated refresh token; `null` if the server did not rotate it. */
        val refreshToken: String?,
        /** Unix-seconds expiration of the new access token. */
        val expiresAt: Long,
    ) : TokenRefreshResult

    data class Failure(
        val error: TokenRefreshError,
    ) : TokenRefreshResult
}

/**
 * Classified refresh error.
 *
 * [isPermanent] is `true` for errors that cannot be recovered by retrying
 * ([NoRefreshToken], [NoDeviceId], [TokenRevoked]) — the caller should fall
 * through to device re-auth. [NetworkError] is transient and may be retried.
 */
sealed class TokenRefreshError(cause: Throwable? = null) : Exception(cause) {
    data object NoRefreshToken : TokenRefreshError()
    data object NoDeviceId : TokenRefreshError()
    data object TokenRevoked : TokenRefreshError()
    data class NetworkError(override val cause: Throwable) : TokenRefreshError(cause)

    val isPermanent: Boolean
        get() = this is TokenRevoked || this is NoRefreshToken || this is NoDeviceId
}

/**
 * Single-flight token refresh coordinator.
 *
 * **Single-flight:** multiple concurrent callers are serialised by [Mutex] — only one
 * `RefreshToken` RPC is made, and all callers see the same cached result as long as it
 * is recent enough (within [CACHE_TTL_MS]).
 *
 * **Canon:** `docs/TOKEN_AUTH.md` §3.3 · §7 (`TokenRefreshCoordinator.kt` checklist item).
 * Mirrors iOS `AuthInterceptor.swift` / `TokenRefreshCoordinator`.
 *
 * **Permanent failure → device re-auth:**
 * If [refreshIfPossible] returns [TokenRefreshResult.Failure] with [TokenRefreshError.isPermanent],
 * the caller should:
 * 1. Call [KeystoreManager.clearTokens] to wipe stale tokens.
 * 2. Trigger device re-authentication via [com.construct.messenger.domain.usecase.LoginUseCase].
 *
 * **Thread-safety:** all public methods are thread-safe (synchronised via [Mutex]).
 */
@Singleton
class TokenRefreshCoordinator @Inject constructor(
    private val keystoreManager: KeystoreManager,
    private val grpcClient: GrpcClient,
) {
    private val mutex = Mutex()

    @Volatile
    private var cachedResult: TokenRefreshResult? = null

    @Volatile
    private var lastRefreshTimeMs: Long = 0L

    /**
     * Attempts a token refresh.
     *
     * Multiple concurrent callers are serialised: only one RPC is made, and each receives
     * the same result (cached for [CACHE_TTL_MS] after the RPC completes).
     *
     * @return [TokenRefreshResult.Success] with the new credentials, or
     *         [TokenRefreshResult.Failure] with a classified [TokenRefreshError].
     */
    suspend fun refreshIfPossible(): TokenRefreshResult {
        return mutex.withLock {
            // Return cached result if fresh enough — this is what gives us
            // single-flight behaviour: after the first caller completes, subsequent
            // callers see the cached value rather than starting a new RPC.
            val now = System.currentTimeMillis()
            val cached = cachedResult
            if (cached != null && now - lastRefreshTimeMs < CACHE_TTL_MS) {
                return@withLock cached
            }

            val refreshToken = keystoreManager.getRefreshToken()
            if (refreshToken == null) {
                val failure = TokenRefreshResult.Failure(TokenRefreshError.NoRefreshToken)
                cacheResult(failure, now)
                return@withLock failure
            }

            val deviceId = keystoreManager.getDeviceId()
            if (deviceId == null) {
                val failure = TokenRefreshResult.Failure(TokenRefreshError.NoDeviceId)
                cacheResult(failure, now)
                return@withLock failure
            }

            val result = performRefresh(refreshToken, deviceId)
            cacheResult(result, now)
            result
        }
    }

    /**
     * Invalidates the cached refresh result, forcing the next call to [refreshIfPossible]
     * to make a fresh RPC even if within the TTL window.
     *
     * Call this after a successful device re-auth to clear stale cached failures.
     */
    fun invalidateCache() {
        cachedResult = null
        lastRefreshTimeMs = 0L
    }

    // ── Private ──────────────────────────────────────────────────────────────────

    private suspend fun performRefresh(
        refreshToken: String,
        deviceId: String,
    ): TokenRefreshResult {
        return try {
            val request = RefreshTokenRequest.newBuilder()
                .setRefreshToken(refreshToken)
                .setDeviceId(deviceId)
                .build()

            val response = grpcClient.auth.refreshToken(request)

            // Persist the rotated access token immediately.
            keystoreManager.saveAccessToken(response.accessToken)
            // Refresh token rotation is optional — only update when the server provides one.
            if (response.hasRefreshToken()) {
                keystoreManager.saveRefreshToken(response.refreshToken)
            }

            TokenRefreshResult.Success(
                accessToken = response.accessToken,
                refreshToken = if (response.hasRefreshToken()) response.refreshToken else null,
                expiresAt = response.expiresAt,
            )
        } catch (e: StatusRuntimeException) {
            when (e.status.code) {
                Status.Code.UNAUTHENTICATED,
                Status.Code.PERMISSION_DENIED,
                    -> TokenRefreshResult.Failure(TokenRefreshError.TokenRevoked)
                else -> TokenRefreshResult.Failure(TokenRefreshError.NetworkError(e))
            }
        } catch (e: Exception) {
            TokenRefreshResult.Failure(TokenRefreshError.NetworkError(e))
        }
    }

    private fun cacheResult(result: TokenRefreshResult, now: Long) {
        cachedResult = result
        lastRefreshTimeMs = now
    }

    private companion object {
        /** How long (ms) a cached refresh result is considered valid.
         * Prevents stampeding the backend on burst arrivals. */
        const val CACHE_TTL_MS = 5_000L
    }
}


