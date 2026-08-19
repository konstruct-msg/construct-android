package com.construct.messenger.service

import com.construct.messenger.crypto.CryptoManager
import com.construct.messenger.data.api.GrpcClient
import com.construct.messenger.data.local.db.UserDao
import com.construct.messenger.data.local.db.UserEntity
import com.construct.messenger.util.DisplayNameGenerator
import shared.proto.core.v1.Crypto.CryptoSuite
import shared.proto.services.v1.KeyServiceOuterClass.GetPreKeyBundleRequest
import shared.proto.services.v1.KeyServiceOuterClass.PreKeyBundle
import uniffi.construct_core.BinaryFirstMessage
import uniffi.construct_core.BinaryKeyBundle
import uniffi.construct_core.DecryptedMessageResult
import uniffi.construct_core.EncryptedMessageComponents
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
    private val userDao: UserDao,
) {

    fun hasSession(contactId: String): Boolean = cryptoManager.hasSession(contactId)

    /**
     * Ensure a live Double-Ratchet session exists for [contactId].
     *
     * @return the peer's X25519 identity public key (needed for sealed sender).
     *   Fetched with the prekey bundle on first init and remembered on [UserEntity].
     *   GetPreKeyBundle is destructive (consumes an OTPK) — never call it just
     *   to read the identity key when a session already exists.
     */
    suspend fun ensureSession(contactId: String): ByteArray? {
        val stored = userDao.getById(contactId)?.identityPublic
        if (cryptoManager.hasSession(contactId)) return stored
        initSession(contactId)
        return userDao.getById(contactId)?.identityPublic
    }

    /** INITIATOR path: fetch [contactId]'s pre-key bundle and start a new session. */
    suspend fun initSession(contactId: String): String {
        val response = grpcClient.key.getPreKeyBundle(
            GetPreKeyBundleRequest.newBuilder()
                .setUserId(contactId)
                .setConsumeOneTimePrekey(true)
                .build(),
        )
        rememberIdentity(contactId, response.bundle.identityKey.toByteArray())
        val bundle = response.bundle.toBinaryKeyBundle(response.verifyingKey.toByteArray())
        return cryptoManager.initSession(contactId, bundle)
    }

    private suspend fun rememberIdentity(contactId: String, identity: ByteArray) {
        val existing = userDao.getById(contactId)
        val base = existing ?: UserEntity(
            id = contactId,
            displayName = DisplayNameGenerator.generate(contactId),
            isContact = true,
        )
        userDao.upsert(base.copy(identityPublic = identity))
    }

    /** RESPONDER path: establish a session from an inbound first message + sender's bundle. */
    fun initReceivingSession(
        contactId: String,
        senderBundle: BinaryKeyBundle,
        firstMessage: BinaryFirstMessage,
    ) = cryptoManager.initReceivingSession(contactId, senderBundle, firstMessage)

    fun encryptMessage(contactId: String, plaintext: String): EncryptedMessageComponents =
        cryptoManager.encryptMessage(contactId, plaintext)

    fun decryptMessage(
        sessionId: String,
        ephemeralPublicKey: ByteArray,
        messageNumber: UInt,
        content: ByteArray,
        suiteId: UShort,
        pqMessageEpoch: UInt,
        pqRatchetField: ByteArray,
    ): DecryptedMessageResult =
        cryptoManager.decryptMessage(
            sessionId, ephemeralPublicKey, messageNumber, content,
            suiteId, pqMessageEpoch, pqRatchetField,
        )

    /** Exports every known session as CFE binary, keyed by contact id — never JSON/base64. */
    fun exportSessions(): Map<String, ByteArray> =
        cryptoManager.getAllSessionContactIds().associateWith { cryptoManager.exportSessionBytes(it) }

    fun importSessions(sessions: Map<String, ByteArray>) {
        sessions.forEach { (contactId, bytes) -> cryptoManager.importSessionBytes(contactId, bytes) }
    }

    fun removeSession(contactId: String): Boolean = cryptoManager.removeSession(contactId)
}

/// Proto `CryptoSuite` enum → the core's SuiteID (`suite_id.rs`): 1 = CLASSIC
/// (X25519+ChaCha20), 2 = PQ_HYBRID (X25519+ML-KEM-768, ML-DSA-65). Mirrors iOS
/// `KeyServiceClient.parseSuiteId` — see construct-docs decision
/// `crypto-suite-extensibility.md`. The raw proto value is NOT the core id
/// (proto classic = 10 → core would reject it as InvalidSuiteId), and suite 3
/// (PQ_RATCHET) is never produced from a bundle: it is negotiated per-session
/// from `supports_pq_ratchet`.
private fun PreKeyBundle.coreSuiteId(): UShort = when (cryptoSuite) {
    CryptoSuite.CRYPTO_SUITE_CLASSIC_X25519_CHACHA20 -> 1u
    // The core has no AES-256 provider — classic, not the ML-KEM hybrid (2).
    CryptoSuite.CRYPTO_SUITE_CLASSIC_X25519_AES256 -> 1u
    CryptoSuite.CRYPTO_SUITE_HYBRID_KYBER768_X25519,
    CryptoSuite.CRYPTO_SUITE_HYBRID_KYBER1024_X25519,
    -> 2u
    else -> 1u
}

private fun PreKeyBundle.toBinaryKeyBundle(verifyingKey: ByteArray): BinaryKeyBundle = BinaryKeyBundle(
    identityPublic = identityKey.toByteArray().toUByteList(),
    signedPrekeyPublic = signedPreKey.toByteArray().toUByteList(),
    signature = signedPreKeySignature.toByteArray().toUByteList(),
    verifyingKey = verifyingKey.toUByteList(),
    suiteId = coreSuiteId(),
    oneTimePrekeyPublic = if (hasOneTimePreKey()) oneTimePreKey.toByteArray().toUByteList() else null,
    oneTimePrekeyId = if (hasOneTimePreKeyId()) oneTimePreKeyId.toUInt() else null,
    spkUploadedAt = spkUploadedAt.toULong(),
    spkRotationEpoch = spkRotationEpoch.toUInt(),
    kyberSpkUploadedAt = if (hasKyberSpkUploadedAt()) kyberSpkUploadedAt.toULong() else 0uL,
    kyberSpkRotationEpoch = if (hasKyberSpkRotationEpoch()) kyberSpkRotationEpoch.toUInt() else 0u,
    kyberPreKeyPublic = if (hasKyberPreKey()) kyberPreKey.toByteArray().toUByteList() else null,
    kyberOneTimePrekeyPublic = if (hasKyberOneTimePreKey()) kyberOneTimePreKey.toByteArray().toUByteList() else null,
    kyberOneTimePrekeyId = if (hasKyberOneTimePreKeyId()) kyberOneTimePreKeyId.toUInt() else null,
    supportsPqRatchet = supportsPqRatchet,
)

private fun ByteArray.toUByteList(): List<UByte> = map { it.toUByte() }
