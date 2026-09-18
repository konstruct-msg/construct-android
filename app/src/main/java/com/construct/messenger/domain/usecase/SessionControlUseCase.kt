package com.construct.messenger.domain.usecase

import android.util.Log
import com.construct.messenger.crypto.CryptoManager
import com.construct.messenger.data.api.MessagingService
import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.data.local.SessionStateStore
import com.construct.messenger.service.OrchestratorGateway
import com.construct.messenger.service.SessionManager
import com.construct.messenger.stealth.StealthPolicy
import com.construct.messenger.stealth.StealthSenderService
import com.construct.messenger.util.KnstFrame
import java.security.SecureRandom
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import shared.proto.core.v1.EnvelopeOuterClass.ContentType
import shared.proto.messaging.v1.Content.SessionControl
import shared.proto.messaging.v1.Content.SessionOp
import uniffi.construct_core.CfeAction
import uniffi.construct_core.CfeIncomingEvent

/**
 * Puts END_SESSION (content type 21) on the wire, then tears down the local session.
 *
 * **Canon:** iOS `MessagingServiceClient.sendEndSession` + `EndSessionPayload`.
 * Type 21 is unencrypted (the ratchet may be dead). Payload is a fixed 1024-byte
 * pad so length does not identify the frame. The reset *reason* is omitted until
 * Android has `sealToIdentity` FFI — a random pad is what a peer without a hint
 * already expects.
 *
 * Stealth: seal with `SealedInner.content_type = SESSION_RESET`. No identity key
 * → skip the RPC (fail-closed) and still archive locally.
 */
@Singleton
class SessionControlUseCase @Inject constructor(
    private val keystoreManager: KeystoreManager,
    private val sessionManager: SessionManager,
    private val sessionStateStore: SessionStateStore,
    private val messagingService: MessagingService,
    private val stealthPolicy: StealthPolicy,
    private val stealthSender: StealthSenderService,
    private val orchestrator: OrchestratorGateway,
    private val cryptoManager: CryptoManager,
) {
    private val random = SecureRandom()
    private val lastSentAt = mutableMapOf<String, Long>()

    suspend fun sendEndSessionToAll() {
        for (id in sessionManager.liveContactIds()) {
            sendEndSession(id, force = true)
        }
    }

    /** RESPONDER → INITIATOR after successful receiving-session init. Type in KNST byte 5. */
    suspend fun sendReady(contactId: String) {
        sendEncryptedControl(contactId, SessionOp.SESSION_OP_READY, ContentType.CONTENT_TYPE_SESSION_READY_VALUE)
    }

    suspend fun sendPing(contactId: String) {
        sendEncryptedControl(contactId, SessionOp.SESSION_OP_PING, ContentType.CONTENT_TYPE_SESSION_PING_VALUE)
    }

    suspend fun sendEndSession(contactId: String, force: Boolean = false): Boolean {
        val target = sessionManager.resolveTarget(contactId) ?: run {
            Log.w(TAG, "END_SESSION skipped — no account/device mapping ${contactId.take(8)}…")
            return false
        }
        val deviceId = target.deviceId
        val accountId = target.accountId
        val now = System.currentTimeMillis()
        val last = lastSentAt[deviceId] ?: 0L
        if (!force && now - last < COOLDOWN_MS) {
            Log.i(TAG, "END_SESSION cooldown ${deviceId.take(8)}…")
            return false
        }
        lastSentAt[deviceId] = now

        val myId = keystoreManager.getUserId() ?: return false
        val payload = ByteArray(PADDED_SIZE).also { random.nextBytes(it) }
        val messageId = UUID.randomUUID().toString().lowercase()
        val timestampMs = System.currentTimeMillis()
        val stealthOn = stealthPolicy.shouldUseSealedSender()
        val identity = target.identityPublic

        val result = try {
            if (stealthOn) {
                if (identity.isEmpty()) {
                    Log.w(TAG, "END_SESSION stealth-on, no IK for ${deviceId.take(8)}… — not identified-downgrading")
                    archiveLocal(deviceId)
                    return false
                }
                val sealed = stealthSender.buildSealedInner(
                    recipientUserId = accountId,
                    recipientIdentityKey = identity,
                    encryptedPayload = payload,
                    contentType = ContentType.CONTENT_TYPE_SESSION_RESET,
                )
                if (MessagingService.SEALED_UNAUTHENTICATED_TRANSPORT) {
                    messagingService.sendSealedMessage(sealed)
                } else {
                    messagingService.sendMessage(
                        messageId = messageId,
                        senderId = myId,
                        recipientId = accountId,
                        conversationId = "",
                        encryptedPayload = ByteArray(0),
                        timestampMs = timestampMs,
                        contentType = ContentType.CONTENT_TYPE_UNSPECIFIED,
                        sealedInner = sealed,
                    )
                }
            } else {
                messagingService.sendMessage(
                    messageId = messageId,
                    senderId = myId,
                    recipientId = accountId,
                    conversationId = "",
                    encryptedPayload = payload,
                    timestampMs = timestampMs,
                    contentType = ContentType.CONTENT_TYPE_SESSION_RESET,
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "END_SESSION RPC failed ${deviceId.take(8)}…", e)
            archiveLocal(deviceId)
            return false
        }

        if (!result.success) {
            Log.w(TAG, "END_SESSION rejected ${result.errorCode}")
        }
        archiveLocal(deviceId)
        return result.success
    }

    suspend fun inboundEndSession(contactId: String) {
        Log.i(TAG, "inbound END_SESSION from ${contactId.take(8)}… — archive local, no bounce")
        archiveLocal(contactId)
    }

    private suspend fun sendEncryptedControl(contactId: String, op: SessionOp, knstType: Int) {
        val target = sessionManager.resolveTarget(contactId) ?: return
        val deviceId = target.deviceId
        val accountId = target.accountId
        val myId = keystoreManager.getUserId() ?: return
        if (!cryptoManager.isMessagingReady || !sessionManager.hasSession(deviceId)) return
        val messageId = UUID.randomUUID().toString().lowercase()
        val payload = SessionControl.newBuilder()
            .setOp(op)
            .setNonce(messageId)
            .build()
            .toByteArray()
        val uuid = runCatching { UUID.fromString(messageId) }.getOrElse { UUID.randomUUID() }
        val plaintext = KnstFrame.pack(payload, knstType, uuid)
        val actions = orchestrator.handleEvent(
            CfeIncomingEvent.OutgoingMessage(deviceId, messageId, plaintext, 0u),
        )
        if (!sessionStateStore.saveCfeActions(actions)) return
        val wire = actions.filterIsInstance<CfeAction.SendEncryptedMessage>()
            .firstOrNull { it.to == deviceId }
            ?.payload
            ?: return
        val timestampMs = System.currentTimeMillis()
        val stealthOn = stealthPolicy.shouldUseSealedSender()
        runCatching {
            if (stealthOn) {
                val sealed = stealthSender.buildSealedInner(
                    recipientUserId = accountId,
                    recipientIdentityKey = target.identityPublic,
                    encryptedPayload = wire,
                    contentType = ContentType.CONTENT_TYPE_UNSPECIFIED,
                )
                messagingService.sendMessage(
                    messageId = messageId,
                    senderId = myId,
                    recipientId = accountId,
                    conversationId = "",
                    encryptedPayload = ByteArray(0),
                    timestampMs = timestampMs,
                    contentType = ContentType.CONTENT_TYPE_UNSPECIFIED,
                    sealedInner = sealed,
                )
            } else {
                messagingService.sendMessage(
                    messageId = messageId,
                    senderId = myId,
                    recipientId = accountId,
                    conversationId = "",
                    encryptedPayload = wire,
                    timestampMs = timestampMs,
                    contentType = ContentType.CONTENT_TYPE_E2EE_SIGNAL,
                )
            }
        }.onFailure { Log.w(TAG, "control $op failed ${deviceId.take(8)}…", it) }
    }

    private suspend fun archiveLocal(contactId: String) {
        runCatching { sessionManager.removeSession(contactId) }
        runCatching {
            sessionStateStore.saveSecureStore(
                uniffi.construct_core.CfeSecureStoreSlot.Session(contactId),
                ByteArray(0),
            )
        }
        runCatching { sessionStateStore.removeMeta(contactId) }
    }

    private companion object {
        const val TAG = "SessionControl"
        const val PADDED_SIZE = 1024
        const val COOLDOWN_MS = 30_000L
    }
}
