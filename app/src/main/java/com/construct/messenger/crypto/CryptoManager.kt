package com.construct.messenger.crypto

import com.construct.messenger.service.OrchestratorGateway
import uniffi.construct_core.BinaryKeyBundle
import uniffi.construct_core.CfeAction
import uniffi.construct_core.CfeIncomingEvent
import uniffi.construct_core.ClassicCryptoCore
import uniffi.construct_core.DecryptedMessageResult
import uniffi.construct_core.DeliveryTarget
import uniffi.construct_core.EncryptedMessageComponents
import uniffi.construct_core.KyberPrekeyUpload
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

    /** Set when the persisted Kyber prekeys would not import into the orchestrator. Cleared by
     * [KyberPrekeyService][com.construct.messenger.crypto.KyberPrekeyService] once a replace-all
     * has reached the server. */
    @Volatile
    var kyberPrekeysLost: Boolean = false

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
     *
     * [savedKyberPrekeys] is the core's Kyber prekey store as last persisted
     * ([exportKyberPrekeys], kept by `KeystoreManager`). It is imported before the orchestrator
     * is published, so no responder init can run against a core that has not got its Kyber
     * secrets back. When it will not import, [kyberPrekeysLost] is set: the server still serves
     * keys whose secrets are gone, and the next publish has to replace them all.
     */
    fun setLocalUserId(userId: String, savedKyberPrekeys: ByteArray? = null) = synchronized(coreLock) {
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
        if (savedKyberPrekeys != null && savedKyberPrekeys.isNotEmpty()) {
            kyberPrekeysLost = runCatching { orch.importKyberPrekeys(savedKyberPrekeys.toUByteList()) }.isFailure
        }
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

    /** X25519 identity **secret** key bytes — needed by
     * [com.construct.messenger.stealth.StealthSenderService] to unseal inbound
     * sender certificates. Never persist or log. */
    fun identityKeyBytes(): ByteArray = synchronized(coreLock) {
        orchestrator?.getIdentityKeyBytes() ?: requireBootstrap().getIdentityKeyBytes()
    }

    // ── Sessions / messages (orchestrator once logged in) ───────────────────

    /**
     * Open a PQXDH v2 session to [contactId]. The core refuses a bundle without a Kyber key it can
     * verify, a hybrid identity and its binding: PQ is mandatory, there is no classical fallback.
     * That refusal is a [uniffi.construct_core.CryptoException.SessionInitializationFailed] whose
     * message contains `PQ_REQUIRED` — see [isPeerNotPostQuantum].
     */
    fun initSession(contactId: String, recipientBundle: BinaryKeyBundle): String = synchronized(coreLock) {
        orchestrator?.initSession(contactId, recipientBundle)
            ?: requireBootstrap().initSession(contactId, recipientBundle)
    }

    /**
     * RESPONDER: open the session from the envelope's `encrypted_payload` exactly as received.
     * The core unpacks it, reads the PQXDH v2 header and decapsulates with its own Kyber secret;
     * nothing here reassembles the first message field by field.
     *
     * When the init used a Kyber one-time key, [SessionInitResult.kyberPrekeys] carries the
     * store without it: persist it (`KyberPrekeyService.persist(blob)`) before anything else, or
     * a restart brings the burned key back.
     */
    fun initReceivingSessionFromWirePayload(
        contactId: String,
        recipientBundle: BinaryKeyBundle,
        wirePayload: ByteArray,
    ): SessionInitResult = synchronized(coreLock) {
        (orchestrator ?: error("orchestrator not ready — setLocalUserId first"))
            .initReceivingSessionFromWirePayload(contactId, recipientBundle, wirePayload.toUByteList())
    }

    fun sessionHealth(contactId: String): uniffi.construct_core.SessionHealthReport? = synchronized(coreLock) {
        orchestrator?.getSessionHealth(contactId)
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

    // ── Kyber prekeys (ML-KEM-1024, PQXDH v2) ───────────────────────────────
    //
    // The core generates, signs and holds every Kyber key; the seeds never leave it, and the
    // responder init decapsulates inside it. The app persists the store and carries the public
    // halves to the server — `KyberPrekeyService`.

    private fun requireOrchestrator(): OrchestratorCore =
        orchestrator ?: error("orchestrator not ready — setLocalUserId first")

    /** The store as one CFE blob (seeds included) — for `KeystoreManager`, never for a log. */
    fun exportKyberPrekeys(): ByteArray = synchronized(coreLock) {
        requireOrchestrator().exportKyberPrekeys().toByteArray()
    }

    /** One-time Kyber keys, each signed by the core over its `created_at` (Ed25519 and hybrid).
     * The hybrid identity must exist ([ensureHybridIdentityPublicKey]). */
    fun generateKyberOneTimePrekeys(count: Int): List<KyberPrekeyUpload> = synchronized(coreLock) {
        requireOrchestrator().generateKyberOneTimePrekeys(count.toUInt())
    }

    fun kyberOneTimePrekeyCount(): Int = synchronized(coreLock) {
        orchestrator?.kyberOneTimePrekeyCount()?.toInt() ?: 0
    }

    /** The committed Kyber SPK as the server should hold it, or null before the first commit. */
    fun currentKyberSpkUpload(): KyberPrekeyUpload? = synchronized(coreLock) {
        requireOrchestrator().currentKyberSpkUpload()
    }

    /** Start a Kyber SPK rotation: a new pending key, signed. Calling it again while one is
     * pending returns the same key, so a retried upload sends what the server may already hold. */
    fun beginKyberSpkRotation(): KyberPrekeyUpload = synchronized(coreLock) {
        requireOrchestrator().beginKyberSpkRotation()
    }

    /** The server confirmed the pending key: it becomes current, the old one is kept 14 days. */
    fun commitKyberSpkRotation(): Boolean = synchronized(coreLock) {
        requireOrchestrator().commitKyberSpkRotation()
    }

    /** The server refused the pending key (it stored nothing): forget it. */
    fun rollbackKyberSpkRotation() = synchronized(coreLock) {
        requireOrchestrator().rollbackKyberSpkRotation()
    }

    /**
     * The hybrid identity key (Ed25519 + ML-DSA-65) that signs every Kyber key, created on first
     * use. It lives in the private-key record: after the first call the caller must persist
     * [exportPrivateKeys] before anything the key signed reaches the server, or a restart comes
     * back with a different key than the one peers pinned.
     */
    fun ensureHybridIdentityPublicKey(): ByteArray = synchronized(coreLock) {
        requireOrchestrator().ensureHybridSignatureKey().toByteArray()
    }

    /** Ed25519 signature binding [hybridPublic] to this device's identity (bundle field 21). */
    fun signHybridIdentityBinding(hybridPublic: ByteArray): ByteArray = synchronized(coreLock) {
        val orch = requireOrchestrator()
        orch.signBundleData(orch.buildHybridIdentityBindMessage(hybridPublic.toUByteList())).toByteArray()
    }

    /** Hybrid signature over the classic SPK's X3DH sign-message (suite 0x01). */
    fun signClassicSpkHybrid(spkPublic: ByteArray): ByteArray = synchronized(coreLock) {
        requireOrchestrator().signHybridPrekey(CLASSIC_SUITE, spkPublic.toUByteList()).toByteArray()
    }

    /** The classic SPK the core currently holds (public half). */
    fun currentSignedPrekeyPublic(): ByteArray = synchronized(coreLock) {
        (orchestrator?.getRegistrationBundleFields() ?: requireBootstrap().getRegistrationBundleFields())
            .signedPrekeyPublic.toByteArray()
    }

    /** Drop all Rust-owned state for a contact, not only its hot ratchet blob. */
    fun forgetContactState(contactId: String) = synchronized(coreLock) {
        (orchestrator ?: error("orchestrator not ready — setLocalUserId first"))
            .forgetContactState(contactId)
    }

    fun rotateSignedPrekey(): uniffi.construct_core.RotatedSpkBundle = synchronized(coreLock) {
        (orchestrator ?: error("orchestrator not ready — setLocalUserId first")).rotateSignedPrekey()
    }

    companion object {
        private const val CLASSIC_SUITE: UByte = 0x01u

        /**
         * True for the initiator's refusal to open a session without PQXDH v2 keys. The core's
         * error is flat: the whole Display text is the message — `Session initialization failed:
         * PQ_REQUIRED: …` — so the code is matched anywhere in it, not as a prefix.
         */
        fun isPeerNotPostQuantum(error: Throwable): Boolean =
            error is uniffi.construct_core.CryptoException.SessionInitializationFailed &&
                error.message.orEmpty().contains("PQ_REQUIRED")
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
        kyberPrekeysLost = false
    }
}

private fun ByteArray.toUByteList(): List<UByte> = map { it.toUByte() }
private fun List<UByte>.toByteArray(): ByteArray = ByteArray(size) { this[it].toByte() }
