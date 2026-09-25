package com.construct.messenger.crypto

import com.construct.messenger.service.OrchestratorGateway
import uniffi.construct_core.BinaryFirstMessage
import uniffi.construct_core.BinaryKeyBundle
import uniffi.construct_core.CfeAction
import uniffi.construct_core.CfeIncomingEvent
import uniffi.construct_core.ClassicCryptoCore
import uniffi.construct_core.DecryptedMessageResult
import uniffi.construct_core.DeliveryTarget
import uniffi.construct_core.EncryptedMessageComponents
import uniffi.construct_core.OrchestratorCore
import uniffi.construct_core.OtpkPair
import uniffi.construct_core.PowSolution
import uniffi.construct_core.RecoveryKeypair
import uniffi.construct_core.ReceivingInitAttempt
import uniffi.construct_core.ReceivingInitCarrier
import uniffi.construct_core.RegistrationBundleFields
import uniffi.construct_core.SessionInitResult
import uniffi.construct_core.TeardownDecision
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
import uniffi.construct_core.planSend as planSendTargets
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

    @Volatile
    private var localIdentityPublic: ByteArray? = null

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
        localIdentityPublic = bundle.identityPublic.toByteArray()
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

    /** Public half used for device-copy routing and peer registry validation. */
    fun currentIdentityPublic(): ByteArray? = synchronized(coreLock) { localIdentityPublic?.copyOf() }

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

    /**
     * Every OTPK private the core holds (CFE binary). Persisted after each generation and
     * consumption — the core keeps them in memory only, and a restart without them leaves
     * the server handing out public halves this device can no longer answer.
     * **Canon:** iOS `OtpkReplenishmentService.persistOtpks`.
     */
    fun exportOneTimePrekeys(): ByteArray = synchronized(coreLock) {
        (orchestrator?.exportOneTimePrekeys() ?: requireBootstrap().exportOneTimePrekeys()).toByteArray()
    }

    /** Restores [exportOneTimePrekeys] output. Call before [setLocalUserId] on a restored
     * identity: the bootstrap core's OTPKs are carried into the orchestrator there. */
    fun importOneTimePrekeys(bytes: ByteArray) = synchronized(coreLock) {
        val list = bytes.toUByteList()
        orchestrator?.importOneTimePrekeys(list) ?: requireBootstrap().importOneTimePrekeys(list)
    }

    /**
     * Whether prekey upload should declare suite 3. The core no longer exports
     * `supports_pq_ratchet()`: every platform build negotiates PQ_RATCHET, and the
     * unsigned bundle flag was the downgrade the cutover removed.
     */
    fun supportsPqRatchet(): Boolean = true

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

    /** Core-owned multi-device delivery plan; account→device translation stays in the app. */
    fun planSend(
        recipientDeviceIds: List<String>,
        ownDeviceIds: List<String>,
        ourDeviceId: String,
        recipientIsSelf: Boolean,
    ): List<DeliveryTarget> = planSendTargets(
        recipientDeviceIds,
        ownDeviceIds,
        ourDeviceId,
        recipientIsSelf,
    )

    /** Core-owned teardown plan over a client-supplied account→device set. */
    fun planTeardown(candidateDeviceIds: List<String>, peerOnDeadSession: Boolean): List<TeardownDecision> =
        synchronized(coreLock) {
            (orchestrator ?: error("orchestrator not ready — setLocalUserId first"))
                .planTeardown(candidateDeviceIds, peerOnDeadSession)
        }

    /** Core-owned two-dimensional receive-init plan: carriers × candidate bundles. */
    fun planReceivingInit(
        carriers: List<ReceivingInitCarrier>,
        bundleCount: Int,
    ): List<ReceivingInitAttempt> = synchronized(coreLock) {
        require(bundleCount >= 0) { "bundleCount must not be negative" }
        (orchestrator ?: error("orchestrator not ready — setLocalUserId first"))
            .planReceivingInit(carriers, bundleCount.toUInt())
    }

    fun deviceCopyTag(
        baseMessageId: String,
        targetDeviceId: String,
        peerIdentityPublic: ByteArray,
    ): String = synchronized(coreLock) {
        uniffi.construct_core.deviceCopyTag(
            baseMessageId,
            targetDeviceId,
            identityKeyBytes().toUByteList(),
            peerIdentityPublic.toUByteList(),
        )
    }

    fun deviceCopyTagMatches(
        tag: String,
        baseMessageId: String,
        ourDeviceId: String,
        peerIdentityPublic: ByteArray,
    ): Boolean = synchronized(coreLock) {
        uniffi.construct_core.deviceCopyTagMatches(
            tag,
            baseMessageId,
            ourDeviceId,
            identityKeyBytes().toUByteList(),
            peerIdentityPublic.toUByteList(),
        )
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
        localIdentityPublic = null
    }
}

private fun ByteArray.toUByteList(): List<UByte> = map { it.toUByte() }
private fun List<UByte>.toByteArray(): ByteArray = ByteArray(size) { this[it].toByte() }
