package com.construct.messenger.service

import com.construct.messenger.crypto.CryptoManager
import com.construct.messenger.data.api.GrpcClient
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
) {

    /** INITIATOR path: fetch [contactId]'s pre-key bundle and start a new session. */
    suspend fun initSession(contactId: String): String {
        val response = grpcClient.key.getPreKeyBundle(
            GetPreKeyBundleRequest.newBuilder().setUserId(contactId).build(),
        )
        val bundle = response.bundle.toBinaryKeyBundle(response.verifyingKey.toByteArray())
        return cryptoManager.initSession(contactId, bundle)
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
}

private fun PreKeyBundle.toBinaryKeyBundle(verifyingKey: ByteArray): BinaryKeyBundle = BinaryKeyBundle(
    identityPublic = identityKey.toByteArray().toUByteList(),
    signedPrekeyPublic = signedPreKey.toByteArray().toUByteList(),
    signature = signedPreKeySignature.toByteArray().toUByteList(),
    verifyingKey = verifyingKey.toUByteList(),
    suiteId = cryptoSuiteValue.toUShort(),
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
