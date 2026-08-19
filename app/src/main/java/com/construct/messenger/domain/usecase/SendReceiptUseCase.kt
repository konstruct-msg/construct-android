package com.construct.messenger.domain.usecase

import android.util.Log
import com.construct.messenger.crypto.CryptoManager
import com.construct.messenger.data.api.MessagingService
import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.data.local.SessionStateStore
import com.construct.messenger.data.local.db.UserDao
import com.construct.messenger.service.OrchestratorGateway
import com.construct.messenger.service.SessionManager
import com.construct.messenger.stealth.StealthPolicy
import com.construct.messenger.stealth.StealthSenderService
import com.construct.messenger.util.KnstFrame
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import shared.proto.core.v1.EnvelopeOuterClass.ContentType
import shared.proto.signaling.v1.Presence.DeliveryReceipt
import shared.proto.signaling.v1.Presence.DirectReceipt
import shared.proto.signaling.v1.Presence.ReceiptStatus
import uniffi.construct_core.CfeAction
import uniffi.construct_core.CfeIncomingEvent

/**
 * E2E delivery receipt (KNST type 14). Not a chat bubble.
 *
 * **Canon:** iOS `OutboundSessionService.sendEncryptedDeliveryReceipt`.
 * Type rides in KNST byte 5. Outer envelope / SealedInner stay unspecified.
 * Fail-closed under stealth. Skip if there is no session.
 */
@Singleton
class SendReceiptUseCase @Inject constructor(
    private val keystoreManager: KeystoreManager,
    private val sessionManager: SessionManager,
    private val orchestrator: OrchestratorGateway,
    private val cryptoManager: CryptoManager,
    private val messagingService: MessagingService,
    private val stealthPolicy: StealthPolicy,
    private val stealthSender: StealthSenderService,
    private val userDao: UserDao,
    private val sessionStateStore: SessionStateStore,
) {
    suspend fun delivered(contactId: String, messageIds: List<String>) {
        if (messageIds.isEmpty()) return
        val myId = keystoreManager.getUserId() ?: return
        if (!cryptoManager.isMessagingReady || !sessionManager.hasSession(contactId)) {
            Log.d(TAG, "receipt skip — no session ${contactId.take(8)}…")
            return
        }

        val receiptId = UUID.randomUUID().toString().lowercase()
        val payload = DeliveryReceipt.newBuilder()
            .setDirect(
                DirectReceipt.newBuilder()
                    .addAllMessageIds(messageIds)
                    .setStatus(ReceiptStatus.RECEIPT_STATUS_DELIVERED)
                    .setTimestamp(System.currentTimeMillis())
                    .setRecipientUserId(contactId),
            )
            .build()
            .toByteArray()
        val uuid = runCatching { UUID.fromString(receiptId) }.getOrElse { UUID.randomUUID() }
        val plaintext = KnstFrame.pack(payload, ContentType.CONTENT_TYPE_DELIVERY_RECEIPT_VALUE, uuid)

        val actions = orchestrator.handleEvent(
            CfeIncomingEvent.OutgoingMessage(
                contactId = contactId,
                messageId = receiptId,
                plaintext = plaintext,
                contentType = 0u,
            ),
        )
        var saved = false
        for (action in actions) {
            if (action is CfeAction.SaveSessionToSecureStore) {
                sessionStateStore.saveSession(action.key, action.data)
                saved = true
            }
        }
        if (!saved) {
            Log.w(TAG, "receipt encrypt without session persist — dropping")
            return
        }
        val wire = actions.filterIsInstance<CfeAction.SendEncryptedMessage>()
            .firstOrNull { it.to == contactId }
            ?.payload
            ?: run {
                Log.w(TAG, "receipt: no SendEncryptedMessage")
                return
            }

        val timestampMs = System.currentTimeMillis()
        val stealthOn = stealthPolicy.shouldUseSealedSender()
        try {
            if (stealthOn) {
                val ik = userDao.getById(contactId)?.identityPublic
                if (ik == null || ik.isEmpty()) {
                    Log.w(TAG, "receipt stealth-on, no IK — dropped")
                    return
                }
                val sealed = stealthSender.buildSealedInner(
                    recipientUserId = contactId,
                    recipientIdentityKey = ik,
                    encryptedPayload = wire,
                    contentType = ContentType.CONTENT_TYPE_UNSPECIFIED,
                )
                if (MessagingService.SEALED_UNAUTHENTICATED_TRANSPORT) {
                    messagingService.sendSealedMessage(sealed)
                } else {
                    messagingService.sendMessage(
                        messageId = receiptId,
                        senderId = myId,
                        recipientId = contactId,
                        conversationId = "",
                        encryptedPayload = ByteArray(0),
                        timestampMs = timestampMs,
                        contentType = ContentType.CONTENT_TYPE_UNSPECIFIED,
                        sealedInner = sealed,
                    )
                }
            } else {
                messagingService.sendMessage(
                    messageId = receiptId,
                    senderId = myId,
                    recipientId = contactId,
                    conversationId = "",
                    encryptedPayload = wire,
                    timestampMs = timestampMs,
                    contentType = ContentType.CONTENT_TYPE_E2EE_SIGNAL,
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "receipt send failed ${contactId.take(8)}…", e)
        }
    }

    private companion object {
        const val TAG = "SendReceipt"
    }
}
