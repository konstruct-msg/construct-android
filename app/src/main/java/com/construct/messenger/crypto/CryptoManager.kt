package com.construct.messenger.crypto

import com.construct.messenger.service.OrchestratorGateway
import uniffi.construct_core.BinaryFirstMessage
import uniffi.construct_core.BinaryKeyBundle
import uniffi.construct_core.CfeAction
import uniffi.construct_core.CfeIncomingEvent
import uniffi.construct_core.ClassicCryptoCore
import uniffi.construct_core.DecryptedMessageResult
import uniffi.construct_core.EncryptedMessageComponents
import uniffi.construct_core.OrchestratorCore
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
import uniffi.construct_core.createOrchestratorCoreFromKeys
import uniffi.construct_core.deriveDeviceId
import uniffi.construct_core.deriveRecoveryKeypair
import uniffi.construct_core.deriveVerifyingKeyFromSecret
import uniffi.construct_core.generateMnemonic
import uniffi.construct_core.mnemonicToSeed
import uniffi.construct_core.signInviteData
import uniffi.construct_core.signRecoveryChallenge
import uniffi.construct_core.verifyInviteSignature
import com.construct.messenger.data.model.IdentityIds
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-level wrapper over the UniFFI cores. Implements [OrchestratorGateway] so
 * [com.construct.messenger.service.MessageProcessor] can drive CFE events.
 *
 * ## Two-phase core (mirrors iOS `CryptoManager`, `API_CRYPTO_GUIDE.md` §2.3)
 *
 *  - **Bootstrap** ([ClassicCryptoCore]) — created by [loadOrCreate] before login.
 *    Used for registration only: key generation, prekey bundle, OTPK generation.
 *    It has NO `handleEvent` — CFE requires the orchestrator.
 *  - **Orchestrator** ([OrchestratorCore]) — built by [setLocalUserId] once the
 *    server user id is known. Becomes THE working core: all session/message ops
 *    route here, and it owns the CFE engine. The bootstrap core is closed and
 *    dropped at that point; its in-session OTPKs are carried over.
 *
 * **Threading:** every native core call is serialized under [coreLock] — the
 * UniFFI cores are not safe for concurrent access (decision doc §"single-thread
 * core access").
 *
 * **Binary pipeline (mandatory):** session/key bytes cross the FFI as raw bytes
 * (CFE — 16-byte header + MessagePack), never JSON/base64. See AGENTS.md.
 */
@Singleton
class CryptoManager @Inject constructor() : OrchestratorGateway {

    private val coreLock = Any()

    @Volatile
    private var bootstrapCore: ClassicCryptoCore? = null

    @Volatile
    private var orchestrator: OrchestratorCore? = null

    /** Account id is an app/network concern; this is the id the core must receive. */
    @Volatile
    private var localDeviceId: String? = null

    /** True once [setLocalUserId] has built the orchestrator — the receive path
     * (CFE `handleEvent`) is only available after this. */
    val isMessagingReady: Boolean
        get() = orchestrator != null

    private fun requireBootstrap(): ClassicCryptoCore =
        bootstrapCore ?: error("CryptoManager not initialized — call loadOrCreate() first")

    /** Creates a fresh core (new identity) or restores one from exported private keys. */
    fun loadOrCreate(savedPrivateKeys: ByteArray? = null): RegistrationBundleFields = synchronized(coreLock) {
        val instance = if (savedPrivateKeys != null) {
            createCryptoCoreFromKeys(savedPrivateKeys.toUByteList())
        } else {
            createCryptoCore()
        }
        bootstrapCore = instance
        val bundle = instance.getRegistrationBundleFields()
        localDeviceId = deriveDeviceId(bundle).also { derived ->
            check(IdentityIds.isCryptoDeviceId(derived)) {
                "construct-core returned invalid local CryptoDeviceId"
            }
        }
        bundle
    }

    /**
     * Promote to the orchestrator once the server-assigned account [userId] is known.
     * The account is retained by the app; only this device's derived CryptoDeviceId crosses
     * into the session core. Both sides of the ratchet AD therefore name devices.
     * Idempotent: updates the id on an existing orchestrator.
     */
    fun setLocalUserId(userId: String) = synchronized(coreLock) {
        require(userId.isNotEmpty()) { "server account id must not be empty" }
        val deviceId = localDeviceId ?: run {
            val current = bootstrapCore?.getRegistrationBundleFields()
                ?: error("CryptoManager not initialized — call loadOrCreate() first")
            deriveDeviceId(current).also { localDeviceId = it }
        }
        check(IdentityIds.isCryptoDeviceId(deviceId)) { "invalid local CryptoDeviceId" }
        orchestrator?.let {
            it.setLocalUserId(deviceId)
            return@synchronized
        }
        val boot = requireBootstrap()
        val orch = createOrchestratorCoreFromKeys(boot.exportPrivateKeys(), deviceId)
        // Carry OTPKs generated this session (registration) into the orchestrator;
        // no-op when the bootstrap core generated none (returning-user login).
        runCatching { orch.importOneTimePrekeys(boot.exportOneTimePrekeys()) }
        orchestrator = orch
        bootstrapCore = null
        boot.close()
    }

    /** Device-space identity currently bound into OrchestratorCore. */
    fun currentDeviceId(): String? = synchronized(coreLock) { localDeviceId }

    // ── OrchestratorGateway (CFE receive path) ──────────────────────────────

    override fun handleEvent(event: CfeIncomingEvent): List<CfeAction> = synchronized(coreLock) {
        (orchestrator ?: error("orchestrator not ready — setLocalUserId first")).handleEvent(event)
    }

    // ── Registration / identity ─────────────────────────────────────────────

    fun exportPrivateKeys(): ByteArray = synchronized(coreLock) {
        (orchestrator?.exportPrivateKeys() ?: requireBootstrap().exportPrivateKeys()).toByteArray()
    }

    fun generateOneTimePrekeys(count: Int): List<OtpkPair> = synchronized(coreLock) {
        orchestrator?.generateOneTimePrekeys(count.toUInt())
            ?: requireBootstrap().generateOneTimePrekeys(count.toUInt())
    }

    /** Whether this build supports SuiteID::PQ_RATCHET (suite 3) — declared on prekey upload. */
    fun supportsPqRatchet(): Boolean = uniffi.construct_core.supportsPqRatchet()

    /** X25519 identity **secret** key bytes — needed by
     * [com.construct.messenger.stealth.StealthSenderService] to unseal inbound
     * sender certificates. Never persist or log. */
    fun identityKeyBytes(): ByteArray = synchronized(coreLock) {
        orchestrator?.getIdentityKeyBytes() ?: requireBootstrap().getIdentityKeyBytes()
    }

    // ── Sessions / messages (orchestrator once logged in) ───────────────────

    fun initSession(contactId: String, recipientBundle: BinaryKeyBundle): String = synchronized(coreLock) {
        orchestrator?.initSession(contactId, recipientBundle)
            ?: requireBootstrap().initSession(contactId, recipientBundle)
    }

    fun initReceivingSession(
        contactId: String,
        recipientBundle: BinaryKeyBundle,
        firstMessage: BinaryFirstMessage,
    ): SessionInitResult = synchronized(coreLock) {
        orchestrator?.initReceivingSession(contactId, recipientBundle, firstMessage)
            ?: requireBootstrap().initReceivingSession(contactId, recipientBundle, firstMessage)
    }

    fun encryptMessage(contactId: String, plaintext: String): EncryptedMessageComponents = synchronized(coreLock) {
        // OrchestratorCore takes UTF-8 bytes (binary pipeline); the legacy
        // ClassicCryptoCore takes the String directly.
        orchestrator?.encryptMessage(contactId, plaintext.toByteArray(Charsets.UTF_8))
            ?: requireBootstrap().encryptMessage(contactId, plaintext)
    }

    /** All suite-3 fields are REQUIRED (no defaults): pass them straight from the
     * wire (`wirePayloadUnpack`) — silently defaulting to classic is how the
     * suite-3 AEAD outage slipped through on iOS. Classic messages carry
     * suiteId=1, pqMessageEpoch=0, empty pqRatchetField. */
    fun decryptMessage(
        sessionId: String,
        ephemeralPublicKey: ByteArray,
        messageNumber: UInt,
        content: ByteArray,
        suiteId: UShort,
        pqMessageEpoch: UInt,
        pqRatchetField: ByteArray,
    ): DecryptedMessageResult = synchronized(coreLock) {
        val ep = ephemeralPublicKey.toUByteList()
        val ct = content.toUByteList()
        val pq = pqRatchetField.toUByteList()
        orchestrator?.decryptMessage(sessionId, ep, messageNumber, ct, suiteId, pqMessageEpoch, pq)
            ?: requireBootstrap().decryptMessage(sessionId, ep, messageNumber, ct, suiteId, pqMessageEpoch, pq)
    }

    fun exportSessionBytes(contactId: String): ByteArray = synchronized(coreLock) {
        (orchestrator?.exportSession(contactId) ?: requireBootstrap().exportSession(contactId)).toByteArray()
    }

    fun importSessionBytes(contactId: String, bytes: ByteArray): String = synchronized(coreLock) {
        orchestrator?.importSession(contactId, bytes.toUByteList())
            ?: requireBootstrap().importSession(contactId, bytes.toUByteList())
    }

    fun removeSession(contactId: String): Boolean = synchronized(coreLock) {
        orchestrator?.removeSession(contactId) ?: requireBootstrap().removeSession(contactId)
    }

    fun getAllSessionContactIds(): List<String> = synchronized(coreLock) {
        orchestrator?.getAllSessionContactIds() ?: requireBootstrap().getAllSessionContactIds()
    }

    fun hasSession(contactId: String): Boolean = synchronized(coreLock) {
        orchestrator?.hasSession(contactId) ?: false
    }

    /** Apply a post-quantum contribution exactly where the core's CFE action says. */
    fun applyPqContribution(contactId: String, kemSharedSecret: ByteArray) = synchronized(coreLock) {
        (orchestrator ?: error("orchestrator not ready — setLocalUserId first"))
            .applyPqContribution(contactId, kemSharedSecret.toUByteList())
    }

    /** CFE coordination snapshots; callers persist the returned bytes in typed slots. */
    fun exportOrchestratorState(): ByteArray = synchronized(coreLock) {
        (orchestrator ?: error("orchestrator not ready — setLocalUserId first"))
            .exportOrchestratorState()
            .toByteArray()
    }

    fun importOrchestratorState(bytes: ByteArray) = synchronized(coreLock) {
        (orchestrator ?: error("orchestrator not ready — setLocalUserId first"))
            .importOrchestratorState(bytes.toUByteList())
    }

    fun exportKyberSessionState(): ByteArray = synchronized(coreLock) {
        (orchestrator ?: error("orchestrator not ready — setLocalUserId first"))
            .exportKyberSessionState()
            .toByteArray()
    }

    fun importKyberSessionState(bytes: ByteArray) = synchronized(coreLock) {
        (orchestrator ?: error("orchestrator not ready — setLocalUserId first"))
            .importKyberSessionState(bytes.toUByteList())
    }

    /** Drop all Rust-owned state for a contact, not only its hot ratchet blob. */
    fun forgetContactState(contactId: String) = synchronized(coreLock) {
        (orchestrator ?: error("orchestrator not ready — setLocalUserId first"))
            .forgetContactState(contactId)
    }

    fun rotateSignedPrekey(): uniffi.construct_core.RotatedSpkBundle = synchronized(coreLock) {
        (orchestrator ?: error("orchestrator not ready — setLocalUserId first")).rotateSignedPrekey()
    }

    // ── Stateless helpers (free functions / no core state) ──────────────────

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

    fun deriveDeviceIdFromIdentity(identityPublic: ByteArray): String =
        deriveDeviceId(identityPublic.toUByteList())

    fun signingKeyBytes(): ByteArray = synchronized(coreLock) {
        (orchestrator?.getSigningKeyBytes() ?: requireBootstrap().getSigningKeyBytes())
    }

    fun signInvite(canonical: String): ByteArray = synchronized(coreLock) {
        signInviteData(canonical, signingKeyBytes().toUByteList()).signature.toByteArray()
    }

    fun verifyInvite(canonical: String, signature: ByteArray, verifyingKey: ByteArray): Boolean =
        verifyInviteSignature(canonical, signature.toUByteList(), verifyingKey.toUByteList())

    fun verifyingKeyFromSigningSecret(): ByteArray = synchronized(coreLock) {
        deriveVerifyingKeyFromSecret(signingKeyBytes().toUByteList()).toByteArray()
    }

    /**
     * Ed25519-signs [message] with this device's signing key. Used for the device
     * auth challenge (`"{device_id}{timestamp}"`) — there is no dedicated FFI export for
     * a bare Ed25519 sign, so this repurposes [signRecoveryChallenge], which is the same
     * primitive (sign(privateKey, message)) under a recovery-specific name.
     */
    fun signWithDeviceKey(message: String): ByteArray = synchronized(coreLock) {
        val signingKey = orchestrator?.getSigningKeyBytes() ?: requireBootstrap().getSigningKeyBytes()
        signRecoveryChallenge(signingKey.toUByteList(), message).toByteArray()
    }

    fun close() = synchronized(coreLock) {
        orchestrator?.close()
        orchestrator = null
        bootstrapCore?.close()
        bootstrapCore = null
        localDeviceId = null
    }
}

private fun ByteArray.toUByteList(): List<UByte> = map { it.toUByte() }
private fun List<UByte>.toByteArray(): ByteArray = ByteArray(size) { this[it].toByte() }
