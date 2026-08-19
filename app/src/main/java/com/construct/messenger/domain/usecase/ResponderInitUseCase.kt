package com.construct.messenger.domain.usecase

import android.util.Log
import com.construct.messenger.crypto.CryptoManager
import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.data.local.SessionStateStore
import com.construct.messenger.service.MessageRouter
import com.construct.messenger.service.OrchestratorGateway
import com.construct.messenger.service.SessionManager
import com.construct.messenger.util.IncomingPlaintext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import uniffi.construct_core.BinaryFirstMessage
import uniffi.construct_core.CfeIncomingEvent
import uniffi.construct_core.wirePayloadUnpack

/**
 * RESPONDER X3DH: fetch the sender's prekey bundle and `initReceivingSession`.
 *
 * **Canon:** iOS `PublicKeyBundleHandler.handlePublicKeyBundleForIncomingMessage`.
 * GetPreKeyBundle is destructive here (consume=true) — this *is* session init.
 */
@Singleton
class ResponderInitUseCase @Inject constructor(
    private val sessionManager: SessionManager,
    private val cryptoManager: CryptoManager,
    private val orchestrator: OrchestratorGateway,
    private val sessionStateStore: SessionStateStore,
    private val keystoreManager: KeystoreManager,
    private val uploadPreKeys: UploadPreKeysUseCase,
) {
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    data class Result(val contactId: String, val messageId: String, val plaintext: ByteArray)

    suspend fun establish(incoming: MessageRouter.IncomingMessage): Result? {
        val contactId = incoming.senderId
        if (!inFlight.add(contactId)) {
            Log.i(TAG, "init already in flight ${contactId.take(8)}…")
            return null
        }
        return try {
            if (!cryptoManager.isMessagingReady) return null
            val bundle = sessionManager.fetchPeerBundle(contactId, consumeOtpk = true)
            val wire = wirePayloadUnpack(incoming.encryptedPayload.map { it.toUByte() })
            val first = BinaryFirstMessage(
                ephemeralPublicKey = wire.dhPublicKey,
                messageNumber = wire.messageNumber,
                content = wire.sealedBox,
                oneTimePrekeyId = wire.oneTimePrekeyId,
                suiteId = wire.suiteId,
                pqMessageEpoch = wire.pqMessageEpoch,
                pqRatchetField = wire.pqRatchetField,
            )
            val init = sessionManager.initReceivingSession(contactId, bundle, first)
            val blob = cryptoManager.exportSessionBytes(contactId)
            sessionStateStore.saveSession("session:$contactId", blob)
            if (sessionStateStore.getEstablishedAt(contactId) == null) {
                sessionStateStore.setEstablishedAt(contactId, System.currentTimeMillis())
            }
            runCatching {
                orchestrator.handleEvent(CfeIncomingEvent.SessionInitCompleted(contactId, blob))
            }
            keystoreManager.getDeviceId()?.let { deviceId ->
                runCatching { uploadPreKeys.replenishIfNeeded(deviceId) }
            }
            val plaintext = init.decryptedMessage.map { it.toByte() }.toByteArray()
            Log.i(TAG, "RESPONDER session for ${contactId.take(8)}… knst=${IncomingPlaintext.isKnst(plaintext)}")
            Result(contactId, incoming.messageId, plaintext)
        } catch (e: Exception) {
            Log.e(TAG, "RESPONDER init failed ${contactId.take(8)}…", e)
            null
        } finally {
            inFlight.remove(contactId)
        }
    }

    private companion object {
        const val TAG = "ResponderInit"
    }
}
