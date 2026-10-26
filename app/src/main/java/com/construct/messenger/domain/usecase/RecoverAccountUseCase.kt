package com.construct.messenger.domain.usecase

import android.os.Build
import com.construct.messenger.crypto.CryptoManager
import com.construct.messenger.data.api.GrpcClient
import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.recovery.RecoveryRepository
import com.google.protobuf.ByteString
import javax.inject.Inject
import shared.proto.core.v1.Identity.DevicePlatform
import shared.proto.services.v1.AuthServiceOuterClass.DevicePublicKeys
import shared.proto.services.v1.AuthServiceOuterClass.NewDeviceForRecovery
import shared.proto.services.v1.AuthServiceOuterClass.RecoverAccountRequest

/** Why a recovery did not start; anything else is the server's answer, thrown. */
enum class RecoverRefusal { INVALID_PHRASE, NO_IDENTIFIER }

/**
 * Sign in to an existing account on this device with its recovery phrase — the account comes back
 * even if every other device is lost. **Canon:** iOS `AccountRecoveryViewModel.submitRecover`.
 *
 * 1. This device gets a fresh identity, as at registration.
 * 2. The phrase's hybrid recovery key (Ed25519 + ML-DSA-65, todo 222) signs a claim handing the
 *    account to exactly that identity: the time, the device id, its verifying and identity keys.
 *    A claim seen once cannot be replayed with other keys, as the old one over a bare timestamp
 *    could. The phrase never leaves the device; an account still on its Ed25519 key is upgraded
 *    by the claim.
 * 3. `RecoverAccount` registers the device. The server signs the account's other devices out
 *    (`devices_revoked`).
 * 4. Tokens, keys and — since the server just accepted a signature by it — the account's address
 *    (the key's Ed25519 half) are stored; one-time prekeys go up in the background.
 */
class RecoverAccountUseCase @Inject constructor(
    private val cryptoManager: CryptoManager,
    private val grpcClient: GrpcClient,
    private val keystoreManager: KeystoreManager,
    private val uploadPreKeysUseCase: UploadPreKeysUseCase,
) {
    /** Returns the new device id, or throws [RecoverRefused] / the RPC's error. */
    suspend operator fun invoke(identifier: String, phrase: String): String {
        val normalizedPhrase = normalizePhrase(phrase)
        if (!cryptoManager.isValidMnemonic(normalizedPhrase)) throw RecoverRefused(RecoverRefusal.INVALID_PHRASE)
        // As registration hashes it: `trim().lowercase()`. iOS lost mixed-case sign-ins to the raw
        // form (2026-07-22); a UUID is unaffected.
        val normalizedIdentifier = identifier.trim().lowercase()
        if (normalizedIdentifier.isEmpty()) throw RecoverRefused(RecoverRefusal.NO_IDENTIFIER)

        keystoreManager.deleteKyberPrekeys()
        val bundle = cryptoManager.loadOrCreate()
        val deviceId = cryptoManager.deriveDeviceId(bundle)
        val publicKeys = DevicePublicKeys.newBuilder()
            .setVerifyingKey(bundle.verifyingKey.toByteString())
            .setIdentityPublic(bundle.identityPublic.toByteString())
            .setSignedPrekeyPublic(bundle.signedPrekeyPublic.toByteString())
            .setSignedPrekeySignature(bundle.signature.toByteString())
            .setCryptoSuite(CLASSIC_CRYPTO_SUITE_LABEL)
            .build()
        val timestamp = System.currentTimeMillis() / 1000
        val claim = cryptoManager.signRecoveryClaim(
            normalizedPhrase, timestamp, deviceId, bundle.verifyingKey, bundle.identityPublic,
        )

        val response = grpcClient.auth.recoverAccount(
            RecoverAccountRequest.newBuilder()
                .setIdentifier(normalizedIdentifier)
                .setRecoveryPublicKey(claim.publicKey.toByteString())
                .setTimestamp(timestamp)
                .setRecoverySignature(claim.signature.toByteString())
                .setNewDevice(
                    NewDeviceForRecovery.newBuilder()
                        .setDeviceId(deviceId)
                        .setDeviceName(Build.MODEL.orEmpty())
                        .setPlatform(DevicePlatform.DEVICE_PLATFORM_ANDROID)
                        .setPublicKeys(publicKeys),
                )
                .build(),
        )

        cryptoManager.setLocalUserId(response.tokens.userId)
        keystoreManager.saveTokens(response.tokens, deviceId)
        keystoreManager.savePrivateKeys(cryptoManager.exportPrivateKeys())
        keystoreManager.saveOwnAccountAddress(RecoveryRepository.addressOf(claim.publicKey))
        uploadPreKeysUseCase(deviceId, count = INITIAL_OTPK_COUNT, replaceExisting = true)
        return deviceId
    }

    private companion object {
        const val CLASSIC_CRYPTO_SUITE_LABEL = "Curve25519+Ed25519"
        const val INITIAL_OTPK_COUNT = 100
    }
}

class RecoverRefused(val reason: RecoverRefusal) : Exception(reason.name)

/** Words lowercased, single spaces — as they were generated. */
internal fun normalizePhrase(phrase: String): String =
    phrase.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

private fun ByteArray.toByteString(): ByteString = ByteString.copyFrom(this)
