package com.construct.messenger.domain.usecase

import com.construct.messenger.crypto.CryptoManager
import com.construct.messenger.data.api.GrpcClient
import com.construct.messenger.data.auth.DeviceAuthRefused
import com.construct.messenger.data.auth.authenticateDevice
import com.construct.messenger.data.local.KeystoreManager
import shared.proto.services.v1.AuthServiceOuterClass.AuthTokensResponse
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
 *
 * A refusal that names its reason is thrown as [DeviceAuthRefused]; everything else as it came.
 */
class LoginUseCase @Inject constructor(
    private val cryptoManager: CryptoManager,
    private val grpcClient: GrpcClient,
    private val keystoreManager: KeystoreManager,
) {
    suspend operator fun invoke(deviceId: String, savedPrivateKeys: ByteArray): AuthTokensResponse {
        cryptoManager.loadOrCreate(savedPrivateKeys)
        restoreOneTimePrekeys(cryptoManager, keystoreManager)

        val tokens = authenticateDevice(cryptoManager, grpcClient, deviceId)
        cryptoManager.setLocalUserId(tokens.userId, keystoreManager.getKyberPrekeys())
        keystoreManager.saveTokens(tokens, deviceId)
        return tokens
    }
}

