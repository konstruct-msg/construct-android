package com.construct.messenger.domain.usecase

import com.construct.messenger.crypto.CryptoManager
import com.construct.messenger.data.api.GrpcClient
import com.construct.messenger.data.local.KeystoreManager
import com.google.protobuf.ByteString
import shared.proto.services.v1.AuthServiceOuterClass.AuthTokensResponse
import shared.proto.services.v1.AuthServiceOuterClass.AuthenticateDeviceRequest
import javax.inject.Inject

/**
 * Device (re-)authentication flow — used once a device is already registered and just
 * needs a fresh token pair (e.g. on app start with no valid session token).
 *
 * **Canon:** `docs/IMPLEMENTATION_PLAN.md` → Phase 3.1, and iOS `AuthViewModel`'s
 * device-auth fallback. The signature is Ed25519 over `"{device_id}{timestamp}"` —
 * must match the server's exact byte format.
 *
 * [savedPrivateKeys] restores the same identity [RegisterUseCase] created — without this,
 * `CryptoManager` would have no core loaded and [CryptoManager.signWithDeviceKey] (and any
 * later `encryptMessage`/`decryptMessage`) would fail.
 */
class LoginUseCase @Inject constructor(
    private val cryptoManager: CryptoManager,
    private val grpcClient: GrpcClient,
    private val keystoreManager: KeystoreManager,
) {
    suspend operator fun invoke(deviceId: String, savedPrivateKeys: ByteArray): AuthTokensResponse {
        cryptoManager.loadOrCreate(savedPrivateKeys)

        val timestamp = System.currentTimeMillis() / 1000
        val signature = cryptoManager.signWithDeviceKey("$deviceId$timestamp")

        val request = AuthenticateDeviceRequest.newBuilder()
            .setDeviceId(deviceId)
            .setTimestamp(timestamp)
            .setSignature(ByteString.copyFrom(signature))
            .build()

        val tokens = grpcClient.auth.authenticateDevice(request).tokens
        cryptoManager.setLocalUserId(tokens.userId, keystoreManager.getKyberPrekeys())
        keystoreManager.saveTokens(tokens, deviceId)
        return tokens
    }
}
