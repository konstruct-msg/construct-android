package com.construct.messenger.data.auth

import com.construct.messenger.crypto.CryptoManager
import com.construct.messenger.data.api.GrpcClient
import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.diagnostics.Log
import com.google.protobuf.ByteString
import io.grpc.StatusRuntimeException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import shared.proto.services.v1.AuthServiceOuterClass.AuthTokensResponse
import shared.proto.services.v1.AuthServiceOuterClass.AuthenticateDeviceRequest
import shared.proto.services.v1.AuthServiceOuterClass.DeviceRefusal

/**
 * A running device whose refresh token the server no longer takes signs in again with its device
 * key. Until 2026-10-19 nothing did: [AuthSessionManager.ensureFresh] logged `TokenRevoked` and
 * went on, every send was refused with an expired token, and a restart changed nothing — a stored
 * token counts as a session (tester log `construct-logs-1792403647`: ten days, TODO 171).
 *
 * Signs with the core already loaded: `LoginUseCase` would build a new bootstrap core beside the
 * running orchestrator. The stored tokens stay until a new pair arrives, so a refusal a VEIL relay
 * forged costs one sign-in, not the session. One attempt at a time, at most one a minute.
 *
 * **Canon:** iOS `DeviceAuthCoordinator`.
 */
@Singleton
class DeviceReauth internal constructor(
    private val cryptoManager: CryptoManager,
    private val grpcClient: GrpcClient,
    private val keystoreManager: KeystoreManager,
    private val removal: DeviceRemoval,
    private val nowMs: () -> Long,
) {
    @Inject
    constructor(cryptoManager: CryptoManager, grpcClient: GrpcClient, keystoreManager: KeystoreManager, removal: DeviceRemoval) :
        this(cryptoManager, grpcClient, keystoreManager, removal, System::currentTimeMillis)

    private val lock = Mutex()
    private var lastAttemptMs: Long? = null

    /**
     * Signs in again unless [refusedToken] — the access token in store when the refresh was
     * refused — has been replaced meanwhile. True when the store now holds a token from a sign-in.
     */
    suspend fun recover(refusedToken: String?): Boolean = lock.withLock {
        if (keystoreManager.getAccessToken() != refusedToken) return true
        val now = nowMs()
        lastAttemptMs?.let { if (now - it < MIN_INTERVAL_MS) return false }
        lastAttemptMs = now
        val deviceId = keystoreManager.getDeviceId() ?: return false

        val tokens = try {
            authenticateDevice(cryptoManager, grpcClient, deviceId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: DeviceAuthRefused) {
            Log.e(TAG, "device re-auth refused (${e.refusal}, direct=${e.overDirectTLS})", e)
            if (e.erasesDevice) removal.heard("device re-auth")
            return false
        } catch (e: Exception) {
            Log.w(TAG, "device re-auth failed — next try in a minute", e)
            return false
        }
        val userId = keystoreManager.getUserId()
        if (userId != null && tokens.userId != userId) {
            Log.e(TAG, "device re-auth answered for another account — tokens not taken")
            return false
        }
        keystoreManager.saveTokens(tokens, deviceId)
        Log.i(TAG, "device re-auth: new tokens after a refused refresh")
        true
    }

    private companion object {
        const val TAG = "DeviceReauth"
        const val MIN_INTERVAL_MS = 60_000L
    }
}

/**
 * The `AuthenticateDevice` call alone, signed by whichever core is loaded: the bootstrap one at
 * sign-in (`LoginUseCase`), the orchestrator when a running device signs in again ([DeviceReauth]).
 * Saves nothing. A refusal that names its reason is thrown as [DeviceAuthRefused]; everything else
 * as it came.
 */
internal suspend fun authenticateDevice(
    cryptoManager: CryptoManager,
    grpcClient: GrpcClient,
    deviceId: String,
): AuthTokensResponse {
    val timestamp = System.currentTimeMillis() / 1000
    val signature = cryptoManager.signWithDeviceKey("$deviceId$timestamp")

    val request = AuthenticateDeviceRequest.newBuilder()
        .setDeviceId(deviceId)
        .setTimestamp(timestamp)
        .setSignature(ByteString.copyFrom(signature))
        .build()

    // Direct before and after: an answer that may erase this device counts only off VEIL.
    val directBefore = grpcClient.veilPort == null
    return try {
        grpcClient.auth.authenticateDevice(request).tokens
    } catch (e: Exception) {
        // grpc-kotlin's stubs throw `StatusException`; until 2026-10-13 only
        // `StatusRuntimeException` was caught here, so no refusal was ever read on a device.
        if (e !is io.grpc.StatusException && e !is StatusRuntimeException) throw e
        val refusal = DeviceRefusalReading.refusalOf(e)
        if (refusal == DeviceRefusal.DEVICE_REFUSAL_UNSPECIFIED) throw e
        throw DeviceAuthRefused(refusal, directBefore && grpcClient.veilPort == null, e)
    }
}
