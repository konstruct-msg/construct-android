package com.construct.messenger.service

import com.construct.messenger.diagnostics.Log
import com.construct.messenger.crypto.CryptoManager
import com.construct.messenger.data.api.GrpcClient
import com.construct.messenger.data.local.ContactStore
import com.construct.messenger.data.local.PeerDeviceRegistry
import com.construct.messenger.data.model.IdentityIds
import shared.proto.core.v1.Crypto.CryptoSuite
import shared.proto.services.v1.KeyServiceOuterClass.GetIdentityKeyRequest
import shared.proto.services.v1.KeyServiceOuterClass.GetPreKeyBundleRequest
import shared.proto.services.v1.KeyServiceOuterClass.GetPreKeyBundlesRequest
import shared.proto.services.v1.KeyServiceOuterClass.PreKeyBundle
import uniffi.construct_core.BinaryKeyBundle
import uniffi.construct_core.SenderCertificate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates the Double-Ratchet session lifecycle: fetches pre-key bundles over gRPC
 * and hands them to [CryptoManager] to establish/advance sessions.
 *
 * **Canon:** `docs/IMPLEMENTATION_PLAN.md` → Phase 3.2 "Session Lifecycle".
 * States: NONE -> INITIALIZING -> ACTIVE -> HEALING -> NONE — healing (Phase 3.3) is not
 * implemented here yet.
 */
@Singleton
class SessionManager @Inject constructor(
    private val cryptoManager: CryptoManager,
    private val grpcClient: GrpcClient,
    private val contacts: ContactStore,
    private val peerDeviceRegistry: PeerDeviceRegistry,
    private val contactKt: com.construct.messenger.security.ContactKt,
) {

    suspend fun hasSession(contactId: String): Boolean {
        val deviceId = peerDeviceRegistry.resolveDeviceId(contactId) ?: return false
        return cryptoManager.hasSession(deviceId)
    }

    data class SessionPeer(
        val accountId: String,
        val deviceId: String,
        val identityPublic: ByteArray,
    )

    /**
     * Ensure a live Double-Ratchet session exists for [contactId].
     *
     * @return the peer's X25519 identity public key (needed for sealed sender).
     *   Fetched with the prekey bundle on first init and remembered on its [com.construct.messenger.data.local.ContactRecord].
     *   GetPreKeyBundle is destructive (consumes an OTPK) — never call it just
     *   to read the identity key when a session already exists.
     */
    suspend fun ensureSession(contactId: String): SessionPeer {
        val deviceId = peerDeviceRegistry.resolveDeviceId(contactId)
        if (deviceId != null && cryptoManager.hasSession(deviceId)) {
            val identity = peerDeviceRegistry.identityForDevice(deviceId)
                ?: contacts.get(contactId)?.identityPublic
                ?: error("missing identity key for $contactId")
            return SessionPeer(
                accountId = peerDeviceRegistry.accountIdForDevice(deviceId) ?: contactId,
                deviceId = deviceId,
                identityPublic = identity,
            )
        }
        val fetched = fetchPeerBundleData(contactId, consumeOtpk = true, deviceId = deviceId)
        openSession(fetched)
        return SessionPeer(fetched.accountId, fetched.deviceId, fetched.identityPublic)
    }

    /** Ensure a session with one explicitly selected device from a multi-device account. */
    /** Devices whose next open must go without a one-time prekey (`SessionRetired`). */
    private val openWithoutOneTimePrekey = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** The core retired our state with [deviceId] and the peer does not hold the one-time
     * prekey it would be given: the next open with it goes without one. */
    fun openNextWithoutOneTimePrekey(deviceId: String) {
        openWithoutOneTimePrekey.add(deviceId)
    }

    suspend fun ensureSessionForDevice(accountOrDeviceId: String, deviceId: String): SessionPeer {
        require(IdentityIds.isCryptoDeviceId(deviceId)) { "invalid peer CryptoDeviceId" }
        val accountId = accountFor(accountOrDeviceId)
        val identity = peerDeviceRegistry.identityForDevice(deviceId)
        if (cryptoManager.hasSession(deviceId) && identity != null) {
            return SessionPeer(accountId, deviceId, identity)
        }
        // Consumed once: the peer said it did not hold the one-time prekey our last handshake
        // named, so this open asks for a bundle without one (3-DH), which it can always
        // reproduce. A later open uses one again. Canon: iOS `SessionReinitHintStore`.
        val withoutOtpk = openWithoutOneTimePrekey.remove(deviceId)
        val fetched = fetchPeerBundleData(accountId, consumeOtpk = !withoutOtpk, deviceId = deviceId)
        openSession(fetched)
        return SessionPeer(fetched.accountId, fetched.deviceId, fetched.identityPublic)
    }

    /**
     * Fetch a peer prekey bundle. [consumeOtpk] must be true only for X3DH init
     * (initiator or responder). Invite verify uses false.
     */
    suspend fun fetchPeerBundle(contactId: String, consumeOtpk: Boolean): BinaryKeyBundle {
        return fetchPeerBundleData(contactId, consumeOtpk).bundle
    }

    suspend fun fetchPeerBundleData(
        contactId: String,
        consumeOtpk: Boolean,
        deviceId: String? = null,
    ): PeerBundle {
        val accountId = accountFor(contactId)
        val request = GetPreKeyBundleRequest.newBuilder()
            .setUserId(accountId)
            .setConsumeOneTimePrekey(consumeOtpk)
        deviceId?.takeIf(IdentityIds::isCryptoDeviceId)?.let(request::setDeviceId)
        val response = grpcClient.sealedKey.getPreKeyBundle(request.build())
        val identity = response.bundle.identityKey.toByteArray()
        val derivedDeviceId = cryptoManager.deriveDeviceIdFromIdentity(identity)
        require(IdentityIds.isCryptoDeviceId(derivedDeviceId)) { "invalid peer CryptoDeviceId" }
        if (response.deviceId.isNotEmpty() && response.deviceId != derivedDeviceId) {
            error("peer device id does not match identity key")
        }
        peerDeviceRegistry.record(accountId, derivedDeviceId, identity)
        rememberIdentity(accountId, identity)
        contactKt.judge(accountId, response)
        return PeerBundle(
            accountId = accountId,
            deviceId = derivedDeviceId,
            identityPublic = identity,
            bundle = response.bundle.toBinaryKeyBundle(response.verifyingKey.toByteArray()),
        )
    }

    /** Refresh the account -> all active device mappings without consuming OTPKs. */
    suspend fun discoverPeerDevices(contactId: String): List<PeerDeviceRegistry.PeerDevice> {
        return discoverPeerBundles(contactId).map { bundle ->
            PeerDeviceRegistry.PeerDevice(bundle.deviceId, bundle.identityPublic)
        }
    }

    /** Fetch every active device bundle without consuming an OTPK. Used by fan-out and receive init. */
    suspend fun discoverPeerBundles(
        contactId: String,
        rememberAsContact: Boolean = true,
    ): List<PeerBundle> {
        val accountId = accountFor(contactId)
        val response = grpcClient.sealedKey.getPreKeyBundles(
            GetPreKeyBundlesRequest.newBuilder()
                .setUserId(accountId)
                .setConsumeOneTimePrekey(false)
                .build(),
        )
        val bundles = response.bundlesList.mapNotNull { entry ->
            val identity = entry.bundle.identityKey.toByteArray()
            if (identity.isEmpty()) return@mapNotNull null
            val derived = cryptoManager.deriveDeviceIdFromIdentity(identity)
            if (!IdentityIds.isCryptoDeviceId(derived)) return@mapNotNull null
            if (entry.deviceId.isNotEmpty() && entry.deviceId != derived) {
                Log.w(TAG, "ignoring mismatched device bundle ${entry.deviceId.take(8)}…")
                return@mapNotNull null
            }
            PeerBundle(
                accountId = accountId,
                deviceId = derived,
                identityPublic = identity,
                platform = entry.platformValue,
                bundle = entry.bundle.toBinaryKeyBundle(entry.verifyingKey.toByteArray()),
            )
        }
        peerDeviceRegistry.recordAll(
            accountId,
            bundles.map { PeerDeviceRegistry.PeerDevice(it.deviceId, it.identityPublic) },
            response.activeDevicesList,
        )
        if (rememberAsContact) {
            bundles.forEach { rememberIdentity(accountId, it.identityPublic) }
        }
        return bundles
    }

    /** Own devices are fetched through the same server answer but are not contacts in Room. */
    /**
     * Devices of [contactId] this device already knows about, without asking the key server.
     *
     * The local half of a peer's device set. A send to someone we already hold sessions with must
     * not wait on the key server — and must not spend a request on it — so the registry answers
     * first and [discoverPeerBundles] only corrects it. The registry is a cache of server answers,
     * not a trust root: every row got in through `accepts`, which re-derives the device id from
     * the identity key it was advertised with.
     */
    suspend fun knownPeerDevices(contactId: String): List<SessionPeer> {
        val accountId = accountFor(contactId)
        return peerDeviceRegistry.knownDevices(accountId).map {
            SessionPeer(
                accountId = it.accountId,
                deviceId = it.deviceId,
                identityPublic = it.identityPublic,
            )
        }
    }

    suspend fun discoverOwnDeviceBundles(accountId: String): List<PeerBundle> =
        discoverPeerBundles(accountId, rememberAsContact = false)

    private suspend fun accountFor(contactId: String): String =
        if (IdentityIds.isCryptoDeviceId(contactId)) {
            peerDeviceRegistry.accountIdForDevice(contactId)
                ?: error("no account mapping for peer device ${contactId.take(8)}…")
        } else {
            contactId
        }

    suspend fun resolveDeviceId(accountOrDeviceId: String): String? =
        peerDeviceRegistry.resolveDeviceId(accountOrDeviceId)

    suspend fun accountIdForDevice(deviceId: String): String? =
        peerDeviceRegistry.accountIdForDevice(deviceId)

    suspend fun knownDeviceIds(accountId: String): List<String> =
        peerDeviceRegistry.knownDevices(accountId).map { it.deviceId }

    suspend fun resolveTarget(accountOrDeviceId: String): SessionPeer? {
        val deviceId = peerDeviceRegistry.resolveDeviceId(accountOrDeviceId) ?: return null
        val accountId = peerDeviceRegistry.accountIdForDevice(deviceId) ?: return null
        val identity = peerDeviceRegistry.identityForDevice(deviceId) ?: return null
        return SessionPeer(accountId, deviceId, identity)
    }

    /** Non-destructive identity key for sealed-sender when we did not init the session. */
    suspend fun fetchIdentityKey(contactId: String): ByteArray? {
        val stored = if (IdentityIds.isCryptoDeviceId(contactId)) {
            peerDeviceRegistry.identityForDevice(contactId)
        } else {
            contacts.get(contactId)?.identityPublic
        }
        if (stored != null && stored.isNotEmpty()) return stored
        val accountId = if (IdentityIds.isCryptoDeviceId(contactId)) {
            peerDeviceRegistry.accountIdForDevice(contactId) ?: return null
        } else {
            contactId
        }
        return runCatching {
            val response = grpcClient.key.getIdentityKey(
                GetIdentityKeyRequest.newBuilder().setUserId(accountId).build(),
            )
            val key = response.identityKey.toByteArray()
            if (key.isEmpty()) return@runCatching stored
            val deviceId = cryptoManager.deriveDeviceIdFromIdentity(key)
            if (IdentityIds.isCryptoDeviceId(deviceId)) {
                peerDeviceRegistry.record(accountId, deviceId, key)
            }
            rememberIdentity(accountId, key)
            key
        }.getOrNull() ?: stored
    }

    /** INITIATOR path: fetch [contactId]'s pre-key bundle and start a new session. */
    suspend fun initSession(contactId: String): String {
        val fetched = fetchPeerBundleData(contactId, consumeOtpk = true)
        return openSession(fetched)
    }

    /**
     * The bundle that answers the core's `OpenSession` for [deviceId] — fetched, and checked to be
     * that device's. Nothing is opened here: [CfeTimerBridge.answerOpenSession] hands the bundle to
     * the core as an event, and the core's answer carries the save and the drained queue.
     *
     * Honours [openNextWithoutOneTimePrekey] like every other open.
     */
    suspend fun bundleForOpenSession(deviceId: String): BinaryKeyBundle {
        require(IdentityIds.isCryptoDeviceId(deviceId)) { "invalid peer CryptoDeviceId" }
        val accountId = peerDeviceRegistry.accountIdForDevice(deviceId)
            ?: error("no account mapping for peer device ${deviceId.take(8)}…")
        val withoutOtpk = openWithoutOneTimePrekey.remove(deviceId)
        val fetched = fetchPeerBundleData(accountId, consumeOtpk = !withoutOtpk, deviceId = deviceId)
        check(fetched.deviceId == deviceId) { "pre-key bundle resolved to a different peer device" }
        return fetched.bundle
    }

    /**
     * `initSession`, with the one refusal that is not a fault said as such: a peer whose bundle
     * has no PQXDH v2 keys (a build before ML-KEM-1024, or keys not published yet) is refused with
     * `PQ_REQUIRED`. PQ is mandatory — there is no classical session to fall back to — so the error
     * still propagates; the log line is what tells it apart from a broken bundle.
     */
    private fun openSession(fetched: PeerBundle): String = try {
        cryptoManager.initSession(fetched.deviceId, fetched.bundle)
    } catch (e: Exception) {
        if (CryptoManager.isPeerNotPostQuantum(e)) {
            Log.w(TAG, "peer device ${fetched.deviceId.take(8)}… has no PQXDH v2 keys — session refused (${e.message})")
        }
        throw e
    }

    private suspend fun rememberIdentity(contactId: String, identity: ByteArray) {
        contacts.rememberIdentity(contactId, identity)
    }

    /**
     * Record the device a receiving session just opened with, and its key, from the certificate
     * it opened from. The sealed replies need them at once (`session_ready` right after a first
     * contact), and the bundle fetch that used to record them on the way no longer happens — iOS
     * found the gap on the stand as `IK_MISS[no_row]` (2026-09-27). Sound only after the open:
     * the core opened with this key because the server's signature on it checked out.
     */
    suspend fun recordOpenedDevice(certificate: SenderCertificate) {
        peerDeviceRegistry.record(certificate.userId, certificate.deviceId, certificate.identityKey)
    }

    /** Exports every known session as CFE binary, keyed by contact id — never JSON/base64. */
    fun exportSessions(): Map<String, ByteArray> =
        cryptoManager.getAllSessionContactIds().associateWith { cryptoManager.exportSessionBytes(it) }

    /**
     * Each stored session into the core, one at a time; returns the devices whose blob it refused.
     * One refusal must not cost the rest — the loop used to stop at the first throw. A refused
     * blob is unusable for good (corrupt, another device's, or a format the core no longer reads:
     * since 0.24.0 every suite-3 session, since 0.26.0 every session without envelope keys —
     * `SESSION_PREDATES_ENVELOPE`), and the device simply has no session: the next send opens
     * one. Each conversation renews once with a handshake. **Canon:** iOS `restoreSessionFromArchive`, which deletes it.
     */
    fun importSessions(sessions: Map<String, ByteArray>): List<String> =
        sessions.mapNotNull { (contactId, bytes) ->
            try {
                cryptoManager.importSessionBytes(contactId, bytes)
                null
            } catch (e: Exception) {
                Log.e(TAG, "session for ${contactId.take(12)}… not restored (unusable — dropped): ${e.message}")
                contactId
            }
        }

    fun removeSession(contactId: String): Boolean = cryptoManager.removeSession(contactId)

    fun liveContactIds(): List<String> = cryptoManager.getAllSessionContactIds()

    data class PeerBundle(
        val accountId: String,
        val deviceId: String,
        val identityPublic: ByteArray,
        val platform: Int = 0,
        val bundle: BinaryKeyBundle,
    )

    private companion object {
        const val TAG = "SessionManager"
    }
}

/// Proto `CryptoSuite` enum → the core's SuiteID (`suite_id.rs`): 1 = CLASSIC
/// (X25519+ChaCha20), 2 = PQ_HYBRID. Mirrors iOS `KeyServiceClient.parseSuiteId` — see
/// construct-docs decision `crypto-suite-extensibility.md`. The raw proto value is NOT the core id
/// (proto classic = 10 → core would reject it as InvalidSuiteId). Suite 4 (PQ_RATCHET) is never
/// produced from a bundle: PQXDH v2 cores open every session on it.
private fun PreKeyBundle.coreSuiteId(): UShort = when (cryptoSuite) {
    CryptoSuite.CRYPTO_SUITE_CLASSIC_X25519_CHACHA20 -> 1u
    // The core has no AES-256 provider — classic, not the ML-KEM hybrid (2).
    CryptoSuite.CRYPTO_SUITE_CLASSIC_X25519_AES256 -> 1u
    CryptoSuite.CRYPTO_SUITE_HYBRID_KYBER768_X25519,
    CryptoSuite.CRYPTO_SUITE_HYBRID_KYBER1024_X25519,
    -> 2u
    else -> 1u
}

/**
 * The served bundle as the core reads it. Under PQXDH v2 the initiator refuses (`PQ_REQUIRED`)
 * unless every Kyber field it checks arrives: the key, its id, its signed `created_at`, both
 * signatures, and the hybrid identity with its binding. A field dropped here does not show up as
 * a wrong value — it shows up as "peer not post-quantum" for every peer — so an absent proto field
 * maps to null, never to an empty or zero value the core would try to verify.
 */
internal fun PreKeyBundle.toBinaryKeyBundle(verifyingKey: ByteArray): BinaryKeyBundle = BinaryKeyBundle(
    identityPublic = identityKey.toByteArray(),
    signedPrekeyPublic = signedPreKey.toByteArray(),
    signature = signedPreKeySignature.toByteArray(),
    verifyingKey = verifyingKey,
    suiteId = coreSuiteId(),
    oneTimePrekeyPublic = if (hasOneTimePreKey()) oneTimePreKey.toByteArray() else null,
    oneTimePrekeyId = if (hasOneTimePreKeyId()) oneTimePreKeyId.toUInt() else null,
    spkUploadedAt = spkUploadedAt.toULong(),
    spkRotationEpoch = spkRotationEpoch.toUInt(),
    kyberSpkUploadedAt = if (hasKyberSpkUploadedAt()) kyberSpkUploadedAt.toULong() else 0uL,
    kyberSpkRotationEpoch = if (hasKyberSpkRotationEpoch()) kyberSpkRotationEpoch.toUInt() else 0u,
    kyberPreKeyPublic = if (hasKyberPreKey()) kyberPreKey.toByteArray() else null,
    kyberPreKeyId = if (hasKyberPreKeyId()) kyberPreKeyId.toUInt() else null,
    kyberPreKeyCreatedAt = if (hasKyberPreKeyCreatedAt()) kyberPreKeyCreatedAt.toULong() else null,
    kyberPreKeySignature = if (hasKyberPreKeySignature()) kyberPreKeySignature.toByteArray() else null,
    kyberPreKeyHybridSignature =
        if (hasKyberPreKeyHybridSignature()) kyberPreKeyHybridSignature.toByteArray() else null,
    kyberOneTimePrekeyPublic = if (hasKyberOneTimePreKey()) kyberOneTimePreKey.toByteArray() else null,
    kyberOneTimePrekeyId = if (hasKyberOneTimePreKeyId()) kyberOneTimePreKeyId.toUInt() else null,
    kyberOneTimePrekeyCreatedAt =
        if (hasKyberOneTimePreKeyCreatedAt()) kyberOneTimePreKeyCreatedAt.toULong() else null,
    kyberOneTimePrekeySignature =
        if (hasKyberOneTimePreKeySignature()) kyberOneTimePreKeySignature.toByteArray() else null,
    kyberOneTimePrekeyHybridSignature =
        if (hasKyberOneTimePreKeyHybridSignature()) kyberOneTimePreKeyHybridSignature.toByteArray() else null,
    hybridIdentityKey = if (hasHybridIdentityKey()) hybridIdentityKey.toByteArray() else null,
    hybridIdentitySignature =
        if (hasHybridIdentitySignature()) hybridIdentitySignature.toByteArray() else null,
)

