package com.construct.messenger.recovery

import com.construct.messenger.crypto.CryptoManager
import com.construct.messenger.data.api.GrpcClient
import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.invite.AccountAddress
import com.google.protobuf.ByteString
import javax.inject.Inject
import javax.inject.Singleton
import shared.proto.services.v1.AuthServiceOuterClass.GetRecoveryStatusRequest
import shared.proto.services.v1.AuthServiceOuterClass.SetRecoveryKeyRequest

/** What the server says about this account's recovery key. */
data class RecoveryStatus(val isSetup: Boolean, val fingerprint: String?)

/** Why a phrase was not accepted on this device. */
enum class ConfirmFailure { INVALID_PHRASE, NOT_SET_UP, OTHER_ACCOUNT }

/**
 * The recovery phrase: set it up for the account, or teach this device the account's address
 * from it. Every path that has the phrase in hand leaves [KeystoreManager] holding the address —
 * the recovery public key — because the phrase is the only source of it this app trusts.
 *
 * **Canon:** iOS `AccountRecoveryViewModel` (setup, confirm).
 */
@Singleton
class RecoveryRepository @Inject constructor(
    private val grpcClient: GrpcClient,
    private val cryptoManager: CryptoManager,
    private val keystoreManager: KeystoreManager,
) {
    fun hasOwnAddress(): Boolean = keystoreManager.getOwnAccountAddress() != null

    fun newPhrase(): List<String> = cryptoManager.generateMnemonic(PHRASE_WORDS).split(" ")

    suspend fun status(): RecoveryStatus {
        val response = grpcClient.auth.getRecoveryStatus(GetRecoveryStatusRequest.getDefaultInstance())
        return RecoveryStatus(
            isSetup = response.isSetup,
            fingerprint = if (response.hasFingerprint()) response.fingerprint else null,
        )
    }

    /** Registers [words] as the account's recovery key and keeps the address it gives. */
    suspend fun setUp(words: List<String>) {
        val userId = keystoreManager.getUserId() ?: error("not authenticated")
        val keypair = cryptoManager.deriveRecoveryKeypair(words.joinToString(" "))
        val timestamp = System.currentTimeMillis() / 1000
        // The message iOS signs, byte for byte; identity-service checks it.
        val signature = cryptoManager.signWithRecoveryKey(
            keypair,
            "CONSTRUCT_RECOVERY_SETUP:$userId:$timestamp",
        )
        val publicKey = keypair.publicKey
        grpcClient.auth.setRecoveryKey(
            SetRecoveryKeyRequest.newBuilder()
                .setRecoveryPublicKey(ByteString.copyFrom(publicKey))
                .setSetupSignature(ByteString.copyFrom(signature))
                .setTimestamp(timestamp)
                .build(),
        )
        keystoreManager.saveOwnAccountAddress(publicKey)
    }

    /**
     * Learns the account's address from [phrase], checked against the fingerprint the server
     * holds. The phrase never leaves the device; the server can only make the check fail.
     * Returns `null` on success.
     */
    suspend fun confirm(phrase: String): ConfirmFailure? {
        val normalized = phrase.trim().lowercase().split(Regex("\\s+")).joinToString(" ")
        if (!cryptoManager.isValidMnemonic(normalized)) return ConfirmFailure.INVALID_PHRASE
        val status = status()
        val fingerprint = status.fingerprint
        if (!status.isSetup || fingerprint == null) return ConfirmFailure.NOT_SET_UP
        val publicKey = cryptoManager.deriveRecoveryKeypair(normalized)
            .publicKey
        if (!AccountAddress.matchesServerFingerprint(publicKey, fingerprint)) {
            return ConfirmFailure.OTHER_ACCOUNT
        }
        keystoreManager.saveOwnAccountAddress(publicKey)
        return null
    }

    companion object {
        const val PHRASE_WORDS = 12
    }
}
