package com.construct.messenger.crypto

import uniffi.construct_core.BinaryFirstMessage
import uniffi.construct_core.BinaryKeyBundle
import uniffi.construct_core.ClassicCryptoCore
import uniffi.construct_core.DecryptedMessageResult
import uniffi.construct_core.EncryptedMessageComponents
import uniffi.construct_core.OtpkPair
import uniffi.construct_core.PowSolution
import uniffi.construct_core.RecoveryKeypair
import uniffi.construct_core.RegistrationBundleFields
import uniffi.construct_core.SessionInitResult
import uniffi.construct_core.PowProgressCallback
import uniffi.construct_core.computePow
import uniffi.construct_core.computePowWithProgress
import uniffi.construct_core.createCryptoCore
import uniffi.construct_core.createCryptoCoreFromKeys
import uniffi.construct_core.deriveDeviceId
import uniffi.construct_core.deriveRecoveryKeypair
import uniffi.construct_core.generateMnemonic
import uniffi.construct_core.mnemonicToSeed
import uniffi.construct_core.signRecoveryChallenge
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-level wrapper over the UniFFI [ClassicCryptoCore] binding.
 *
 * **Binary pipeline (mandatory):** [exportSessionBytes]/[importSessionBytes] cross the
 * UniFFI boundary as raw bytes (CFE — 16-byte header + MessagePack), never JSON/base64
 * strings. See `construct-messenger/AGENTS.md` §"Binary Data Pipeline".
 */
@Singleton
class CryptoManager @Inject constructor() {

    @Volatile
    private var core: ClassicCryptoCore? = null

    private fun requireCore(): ClassicCryptoCore =
        core ?: error("CryptoManager not initialized — call loadOrCreate() first")

    /** Creates a fresh core (new identity) or restores one from exported private keys. */
    fun loadOrCreate(savedPrivateKeys: ByteArray? = null): RegistrationBundleFields {
        val instance = if (savedPrivateKeys != null) {
            createCryptoCoreFromKeys(savedPrivateKeys.toUByteList())
        } else {
            createCryptoCore()
        }
        core = instance
        return instance.getRegistrationBundleFields()
    }

    fun setLocalUserId(userId: String) = requireCore().setLocalUserId(userId)

    fun exportPrivateKeys(): ByteArray = requireCore().exportPrivateKeys().toByteArray()

    fun generateOneTimePrekeys(count: Int): List<OtpkPair> =
        requireCore().generateOneTimePrekeys(count.toUInt())

    /** Whether this build supports SuiteID::PQ_RATCHET (suite 3) — declared on prekey upload. */
    fun supportsPqRatchet(): Boolean = uniffi.construct_core.supportsPqRatchet()

    fun initSession(contactId: String, recipientBundle: BinaryKeyBundle): String =
        requireCore().initSession(contactId, recipientBundle)

    fun initReceivingSession(
        contactId: String,
        recipientBundle: BinaryKeyBundle,
        firstMessage: BinaryFirstMessage,
    ): SessionInitResult = requireCore().initReceivingSession(contactId, recipientBundle, firstMessage)

    fun encryptMessage(contactId: String, plaintext: String): EncryptedMessageComponents =
        requireCore().encryptMessage(contactId, plaintext)

    fun decryptMessage(
        sessionId: String,
        ephemeralPublicKey: ByteArray,
        messageNumber: UInt,
        content: ByteArray,
    ): DecryptedMessageResult = requireCore().decryptMessage(
        sessionId,
        ephemeralPublicKey.toUByteList(),
        messageNumber,
        content.toUByteList(),
    )

    fun exportSessionBytes(contactId: String): ByteArray = requireCore().exportSession(contactId).toByteArray()

    fun importSessionBytes(contactId: String, bytes: ByteArray): String =
        requireCore().importSession(contactId, bytes.toUByteList())

    fun removeSession(contactId: String): Boolean = requireCore().removeSession(contactId)

    fun getAllSessionContactIds(): List<String> = requireCore().getAllSessionContactIds()

    fun generateMnemonic(wordCount: Int): String = generateMnemonic(wordCount.toUByte())

    fun deriveRecoveryKeypair(mnemonic: String): RecoveryKeypair =
        deriveRecoveryKeypair(mnemonicToSeed(mnemonic))

    fun computePow(challenge: String, difficulty: Int): PowSolution =
        computePow(challenge, difficulty.toUInt())

    /** As [computePow], but reports estimated progress (0f..1f) while the nonce search runs. */
    fun computePow(challenge: String, difficulty: Int, onProgress: (Float) -> Unit): PowSolution =
        computePowWithProgress(
            challenge,
            difficulty.toUInt(),
            object : PowProgressCallback {
                override fun onProgress(currentNonce: ULong, attempts: ULong, estimatedProgress: Float) {
                    onProgress(estimatedProgress)
                }
            },
        )

    /** Deterministic device id derived from this identity's public key — matches iOS `deriveDeviceId`. */
    fun deriveDeviceId(bundle: RegistrationBundleFields): String = deriveDeviceId(bundle.identityPublic)

    /**
     * Ed25519-signs [message] with this device's signing key. Used for the device
     * auth challenge (`"{device_id}{timestamp}"`) — there is no dedicated FFI export for
     * a bare Ed25519 sign, so this repurposes [signRecoveryChallenge], which is the same
     * primitive (sign(privateKey, message)) under a recovery-specific name.
     */
    fun signWithDeviceKey(message: String): ByteArray =
        signRecoveryChallenge(requireCore().getSigningKeyBytes().toUByteList(), message).toByteArray()

    fun close() {
        core?.close()
        core = null
    }
}

private fun ByteArray.toUByteList(): List<UByte> = map { it.toUByte() }
private fun List<UByte>.toByteArray(): ByteArray = ByteArray(size) { this[it].toByte() }
