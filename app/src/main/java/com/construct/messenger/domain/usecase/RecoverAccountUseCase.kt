package com.construct.messenger.domain.usecase

import android.os.Build
import com.construct.messenger.crypto.CryptoManager
import com.construct.messenger.data.api.GrpcClient
import com.construct.messenger.data.local.KeystoreManager
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
 * 1. The phrase gives the recovery keypair; it never leaves the device. A timestamp challenge is
 *    signed with it — the server checks it against the account's recovery key.
 * 2. This device gets a fresh identity, as at registration, and `RecoverAccount` registers it.
 *    The server signs the account's other devices out (`devices_revoked`).
 * 3. Tokens, keys and — since the server just accepted a signature by it — the account's address
 *    are stored; one-time prekeys go up in the background, as after registration.
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

        val recovery = cryptoManager.deriveRecoveryKeypair(normalizedPhrase)
        val challenge = (System.currentTimeMillis() / 1000).toString()
        val signature = cryptoManager.signWithRecoveryKey(recovery, challenge)

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

        val response = grpcClient.auth.recoverAccount(
            RecoverAccountRequest.newBuilder()
                .setIdentifier(normalizedIdentifier)
                .setChallenge(challenge)
                .setRecoverySignature(ByteString.copyFrom(signature))
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
        keystoreManager.saveOwnAccountAddress(recovery.publicKey.toByteArray())
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

private fun List<UByte>.toByteString(): ByteString = ByteString.copyFrom(ByteArray(size) { this[it].toByte() })
private fun List<UByte>.toByteArray(): ByteArray = ByteArray(size) { this[it].toByte() }
