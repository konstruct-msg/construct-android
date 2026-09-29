package com.construct.messenger.crypto

import com.construct.messenger.service.OrchestratorGateway
import uniffi.construct_core.BinaryKeyBundle
import uniffi.construct_core.CfeAction
import uniffi.construct_core.CfeIncomingEvent
import uniffi.construct_core.computeSafetyNumber
import uniffi.construct_core.ClassicCryptoCore
import uniffi.construct_core.DeliveryTarget
import uniffi.construct_core.KyberPrekeyUpload
import uniffi.construct_core.OrchestratorCore
import uniffi.construct_core.OtpkPair
import uniffi.construct_core.PowSolution
import uniffi.construct_core.RecoveryKeypair
import uniffi.construct_core.ReceivingOpenResult
import uniffi.construct_core.RegistrationBundleFields
import uniffi.construct_core.SenderCertificate
import uniffi.construct_core.PowProgressCallback
import uniffi.construct_core.computePow
import uniffi.construct_core.computePowWithProgress
import uniffi.construct_core.createCryptoCore
import uniffi.construct_core.createCryptoCoreFromKeys
import uniffi.construct_core.createOrchestratorCoreFromKeys
import uniffi.construct_core.deriveDeviceId
import uniffi.construct_core.deriveRecoveryKeypair
import uniffi.construct_core.generateMnemonic
import uniffi.construct_core.mnemonicToSeed
import uniffi.construct_core.planSend as planSendTargets
import uniffi.construct_core.signRecoveryChallenge
import uniffi.construct_core.validateMnemonic
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
            createCryptoCoreFromKeys(savedPrivateKeys)
        } else {
            createCryptoCore()
        }
        bootstrapCore = instance
        val bundle = instance.getRegistrationBundleFields()
        localIdentityPublic = bundle.identityPublic
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
            kyberPrekeysLost = runCatching { orch.importKyberPrekeys(savedKyberPrekeys) }.isFailure
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
        (orchestrator?.exportPrivateKeys() ?: requireBootstrap().exportPrivateKeys())
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
        (orchestrator?.exportOneTimePrekeys() ?: requireBootstrap().exportOneTimePrekeys())
    }

    /** Restores [exportOneTimePrekeys] output. Call before [setLocalUserId] on a restored
     * identity: the bootstrap core's OTPKs are carried into the orchestrator there. */
    fun importOneTimePrekeys(bytes: ByteArray) = synchronized(coreLock) {
        val list = bytes
        orchestrator?.importOneTimePrekeys(list) ?: requireBootstrap().importOneTimePrekeys(list)
    }

    /**
     * Opens a box sealed to this device's X25519 identity key — an inbound sender certificate —
     * inside the core. Until 2026-09-29 the identity secret was read out for this on every
     * sealed message. Throws when the box is sealed to another key.
     */
    fun openSealedToDevice(sealedBox: ByteArray): ByteArray = synchronized(coreLock) {
        requireOrchestrator().openSealedToDevice(sealedBox)
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
     * Open a receiving session from what the core holds queued for [device]: each queued message
     * opens with the key its sender certificate names, once the core has checked the server's
     * signature against [trustedServerKeys]. Nothing is fetched
     * (`decisions/first-message-opens-without-the-server.md`).
     *
     * The keys are handed over before every open rather than once: the fetched key can arrive or
     * rotate while the app runs, and a stale copy in the core would refuse certificates the app
     * accepts. When the open burned a Kyber one-time key, [ReceivingOpenResult.kyberPrekeys]
     * carries the store without it — persist it before anything else.
     */
    fun openReceiving(device: String, trustedServerKeys: List<ByteArray>): ReceivingOpenResult =
        synchronized(coreLock) {
            val core = orchestrator ?: error("orchestrator not ready — setLocalUserId first")
            core.setTrustedServerKeys(trustedServerKeys)
            core.openReceiving(device)
        }

    fun sessionHealth(contactId: String): uniffi.construct_core.SessionHealthReport? = synchronized(coreLock) {
        orchestrator?.getSessionHealth(contactId)
    }

    fun exportSessionBytes(contactId: String): ByteArray = synchronized(coreLock) {
        (orchestrator?.exportSession(contactId) ?: requireBootstrap().exportSession(contactId))
    }

    fun importSessionBytes(contactId: String, bytes: ByteArray): String = synchronized(coreLock) {
        orchestrator?.importSession(contactId, bytes)
            ?: requireBootstrap().importSession(contactId, bytes)
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

    /**
     * Retire our current state with [deviceId] — the person reset the session. The core keeps it
     * as a previous state (what the peer still sends on it decrypts) and the next send opens a
     * new one; nothing is sent now. Returns the save to execute, empty when nothing was current.
     * Replaced END_SESSION's `planTeardown` on 2026-09-27.
     */
    fun retireSession(deviceId: String): List<CfeAction> = synchronized(coreLock) {
        orchestrator?.retireSession(deviceId) ?: emptyList()
    }

    fun deviceCopyTag(
        baseMessageId: String,
        targetDeviceId: String,
        peerIdentityPublic: ByteArray,
    ): String = synchronized(coreLock) {
        requireOrchestrator().deviceCopyTag(baseMessageId, targetDeviceId, peerIdentityPublic)
    }

    /** Whether [tag] was written for this device by the device behind [peerIdentityPublic]. The
     * core derives this device's id from its own key; a caller cannot pass the wrong one. */
    fun deviceCopyTagMatches(
        tag: String,
        baseMessageId: String,
        peerIdentityPublic: ByteArray,
    ): Boolean = synchronized(coreLock) {
        requireOrchestrator().deviceCopyTagMatches(tag, baseMessageId, peerIdentityPublic)
    }

    /** CFE coordination snapshots; callers persist the returned bytes in typed slots. */
    fun exportOrchestratorState(): ByteArray = synchronized(coreLock) {
        (orchestrator ?: error("orchestrator not ready — setLocalUserId first"))
            .exportOrchestratorState()
            
    }

    fun importOrchestratorState(bytes: ByteArray) = synchronized(coreLock) {
        (orchestrator ?: error("orchestrator not ready — setLocalUserId first"))
            .importOrchestratorState(bytes)
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
        requireOrchestrator().exportKyberPrekeys()
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
        requireOrchestrator().ensureHybridSignatureKey()
    }

    /** Ed25519 signature binding [hybridPublic] to this device's identity (bundle field 21). */
    fun signHybridIdentityBinding(hybridPublic: ByteArray): ByteArray = synchronized(coreLock) {
        val orch = requireOrchestrator()
        orch.signBundleData(orch.buildHybridIdentityBindMessage(hybridPublic))
    }

    /** Hybrid signature over the classic SPK's X3DH sign-message (suite 0x01). */
    fun signClassicSpkHybrid(spkPublic: ByteArray): ByteArray = synchronized(coreLock) {
        requireOrchestrator().signHybridPrekey(CLASSIC_SUITE, spkPublic)
    }

    /** The classic SPK the core currently holds (public half). */
    fun currentSignedPrekeyPublic(): ByteArray = synchronized(coreLock) {
        (orchestrator?.getRegistrationBundleFields() ?: requireBootstrap().getRegistrationBundleFields())
            .signedPrekeyPublic
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

    fun isValidMnemonic(mnemonic: String): Boolean = validateMnemonic(mnemonic)

    /** Signs [message] with a recovery private key — the `SetRecoveryKey` proof of possession. */
    fun signWithRecoveryKey(keypair: RecoveryKeypair, message: String): ByteArray =
        signRecoveryChallenge(keypair.privateKey, message)

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
        deriveDeviceId(identityPublic)

    /**
     * The 60-digit number two people compare for one pair of devices, computed by the core so
     * both clients print the same. `null` when the core cannot read an id: there is no partial
     * safety number, and a number made from nothing would match for everyone.
     */
    fun safetyNumber(myDeviceId: String, theirDeviceId: String): String? =
        computeSafetyNumber(myDeviceId, theirDeviceId)

    fun signInvite(canonical: String): ByteArray = signWithDeviceKey(canonical)

    fun verifyInvite(canonical: String, signature: ByteArray, verifyingKey: ByteArray): Boolean =
        verifyInviteSignature(canonical, signature, verifyingKey)

    /** The Ed25519 verifying key this device publishes — what a recipient checks its invite with. */
    fun verifyingKey(): ByteArray = synchronized(coreLock) {
        (orchestrator?.getRegistrationBundleFields() ?: requireBootstrap().getRegistrationBundleFields())
            .verifyingKey
    }

    /**
     * Ed25519-signs [message] with this device's signing key, inside the core: the device auth
     * challenge (`"{device_id}{timestamp}"`) and invites. Until 2026-09-29 the key was read out
     * with `getSigningKeyBytes` and signed through the recovery function. Before login the
     * bootstrap core signs (`signBundleData` is the same plain Ed25519 under its first use's name).
     */
    fun signWithDeviceKey(message: String): ByteArray = synchronized(coreLock) {
        val bytes = message.toByteArray(Charsets.UTF_8)
        orchestrator?.signWithDeviceKey(bytes) ?: requireBootstrap().signBundleData(bytes)
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

