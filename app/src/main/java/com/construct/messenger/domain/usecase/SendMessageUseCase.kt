package com.construct.messenger.domain.usecase

import android.util.Log
import com.construct.messenger.crypto.CryptoManager
import com.construct.messenger.data.api.MessagingService
import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.data.local.SessionStateStore
import com.construct.messenger.data.local.db.ChatDao
import com.construct.messenger.data.local.db.ChatEntity
import com.construct.messenger.data.local.db.MessageDao
import com.construct.messenger.data.local.db.MessageEntity
import com.construct.messenger.data.local.db.UserDao
import com.construct.messenger.data.local.db.UserEntity
import com.construct.messenger.data.model.DeliveryStatus
import com.construct.messenger.service.OrchestratorGateway
import com.construct.messenger.service.SessionManager
import com.construct.messenger.stealth.StealthPolicy
import com.construct.messenger.stealth.StealthSenderService
import com.construct.messenger.util.ConversationId
import com.construct.messenger.util.DisplayNameGenerator
import com.construct.messenger.util.KnstFrame
import com.construct.messenger.util.SenderSyncRouting
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import shared.proto.core.v1.EnvelopeOuterClass.ContentType
import shared.proto.messaging.v1.Content.MessageContent
import shared.proto.messaging.v1.Content.TextMessage
import uniffi.construct_core.CfeAction
import uniffi.construct_core.CfeIncomingEvent
import uniffi.construct_core.DeliveryAudience

sealed interface SendOutcome {
    data class Sent(val messageId: String) : SendOutcome
    data class Failed(val messageId: String, val reason: String) : SendOutcome
}

/**
 * 1:1 text send.
 *
 * **Canon:** iOS `ChatSendCoordinator.sendTextMessage` + `OutboundSessionService.encryptOutgoing`.
 *
 * 1. Optimistic Room row (SENDING).
 * 2. `MessageContent` proto → KNST frame (type in byte 5).
 * 3. Ensure Double-Ratchet session (prekey fetch is destructive — only if missing).
 * 4. CFE `OutgoingMessage` → `SendEncryptedMessage` payload. Session blob must be
 *    persisted **before** the unary send (sender-state-durability-before-send).
 * 5. Stealth: sealed inner with `SealedInner.content_type` unspecified. Fail closed
 *    — never identified-downgrade when stealth is on.
 * 6. Unary send. Identified envelope carries sender+recipient only, no conversation_id.
 */
class SendMessageUseCase @Inject constructor(
    private val keystoreManager: KeystoreManager,
    private val sessionManager: SessionManager,
    private val orchestrator: OrchestratorGateway,
    private val cryptoManager: CryptoManager,
    private val messagingService: MessagingService,
    private val stealthPolicy: StealthPolicy,
    private val stealthSender: StealthSenderService,
    private val messageDao: MessageDao,
    private val chatDao: ChatDao,
    private val userDao: UserDao,
    private val sessionStateStore: SessionStateStore,
) {
    suspend operator fun invoke(contactId: String, text: String): SendOutcome {
        val body = text.trim()
        require(body.isNotEmpty()) { "empty message" }

        val myId = keystoreManager.getUserId()
            ?: return SendOutcome.Failed("", "not authenticated")
        require(cryptoManager.isMessagingReady) { "orchestrator not ready" }

        val messageId = UUID.randomUUID().toString().lowercase()
        val timestampMs = System.currentTimeMillis()
        val chatId = ConversationId.direct(myId, contactId)

        persistOutgoing(chatId, contactId, messageId, body, timestampMs, DeliveryStatus.SENDING)

        return try {
            val peer = sessionManager.ensureSession(contactId)
            val plaintext = knstText(body, messageId)
            val actions = orchestrator.handleEvent(
                CfeIncomingEvent.OutgoingMessage(
                    contactId = peer.deviceId,
                    messageId = messageId,
                    plaintext = plaintext,
                    contentType = 0u,
                ),
            )
            persistSessionActions(actions)
            val wire = actions.filterIsInstance<CfeAction.SendEncryptedMessage>()
                .firstOrNull { it.to == peer.deviceId }
                ?.payload
                ?: error("orchestrator returned no SendEncryptedMessage")

            val stealthOn = stealthPolicy.shouldUseSealedSender()
            val sealed = if (stealthOn) {
                val ik = peer.identityPublic
                    .takeIf { it.isNotEmpty() }
                    ?: error("stealth on but no recipient identity key — refusing identified downgrade")
                stealthSender.buildSealedInner(
                    recipientUserId = contactId,
                    recipientIdentityKey = ik,
                    encryptedPayload = wire,
                    contentType = ContentType.CONTENT_TYPE_UNSPECIFIED,
                )
            } else {
                null
            }

            var lastError = "send failed"
            repeat(MAX_ATTEMPTS) { attempt ->
                val result = try {
                    if (sealed != null) {
                        if (MessagingService.SEALED_UNAUTHENTICATED_TRANSPORT) {
                            messagingService.sendSealedMessage(sealed)
                        } else {
                            messagingService.sendMessage(
                                messageId = messageId,
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
                            messageId = messageId,
                            senderId = myId,
                            recipientId = contactId,
                            conversationId = "",
                            encryptedPayload = wire,
                            timestampMs = timestampMs,
                            contentType = ContentType.CONTENT_TYPE_E2EE_SIGNAL,
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (attempt == MAX_ATTEMPTS - 1) {
                        messageDao.updateDeliveryStatus(messageId, DeliveryStatus.FAILED.name)
                        return SendOutcome.Failed(messageId, e.message ?: "send failed")
                    }
                    delay(BACKOFF_MS * (attempt + 1))
                    return@repeat
                }
                if (result.success) {
                    messageDao.updateDeliveryStatus(messageId, DeliveryStatus.SENT.name)
                    runCatching {
                        fanOutCopies(
                            myId = myId,
                            contactId = contactId,
                            baseMessageId = messageId,
                            timestampMs = timestampMs,
                            plaintext = plaintext,
                            primary = peer,
                        )
                    }.onFailure {
                        // The primary copy is already durable and accepted. A linked-device
                        // copy is best-effort and can be retried by the next send/reconcile pass.
                        Log.w(TAG, "per-device fan-out failed ${messageId.take(8)}…", it)
                    }
                    return SendOutcome.Sent(messageId)
                }
                lastError = result.errorCode.ifEmpty { "send failed" }
                if (!result.retryable || attempt == MAX_ATTEMPTS - 1) {
                    messageDao.updateDeliveryStatus(messageId, DeliveryStatus.FAILED.name)
                    return SendOutcome.Failed(messageId, lastError)
                }
                val wait = result.retryAfterMs.coerceAtLeast(BACKOFF_MS) * (attempt + 1)
                delay(wait)
            }
            messageDao.updateDeliveryStatus(messageId, DeliveryStatus.FAILED.name)
            SendOutcome.Failed(messageId, lastError)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "send failed ${messageId.take(8)}…", e)
            messageDao.updateDeliveryStatus(messageId, DeliveryStatus.FAILED.name)
            SendOutcome.Failed(messageId, e.message ?: "send failed")
        }
    }

    private suspend fun persistSessionActions(actions: List<CfeAction>) {
        if (!sessionStateStore.saveCfeActions(actions)) {
            error("session-state persist missing after encrypt — refusing to release ciphertext")
        }
    }

    private suspend fun persistOutgoing(
        chatId: String,
        contactId: String,
        messageId: String,
        text: String,
        timestampMs: Long,
        status: DeliveryStatus,
    ) {
        messageDao.insert(
            MessageEntity(
                id = messageId,
                chatId = chatId,
                text = text,
                isSentByMe = true,
                timestamp = timestampMs,
                deliveryStatus = status.name,
            ),
        )
        val existing = chatDao.getById(chatId)
        if (existing == null) {
            chatDao.upsert(
                ChatEntity(
                    id = chatId,
                    otherUserId = contactId,
                    lastMessageText = text,
                    lastMessageTime = timestampMs,
                    unreadCount = 0,
                ),
            )
        } else {
            chatDao.updateLastMessage(chatId, text, timestampMs)
        }
        if (userDao.getById(contactId) == null) {
            userDao.upsert(
                UserEntity(
                    id = contactId,
                    displayName = DisplayNameGenerator.generate(contactId),
                    isContact = true,
                ),
            )
        }
    }

    private suspend fun fanOutCopies(
        myId: String,
        contactId: String,
        baseMessageId: String,
        timestampMs: Long,
        plaintext: ByteArray,
        primary: SessionManager.SessionPeer,
    ) {
        val ourDeviceId = cryptoManager.currentDeviceId() ?: return
        val recipientBundles = if (contactId == myId) {
            emptyList()
        } else {
            runCatching { sessionManager.discoverPeerBundles(contactId) }
                .getOrElse {
                    Log.w(TAG, "recipient device discovery failed ${contactId.take(8)}…", it)
                    emptyList()
                }
        }
        val ownBundles = runCatching { sessionManager.discoverOwnDeviceBundles(myId) }
            .getOrElse {
                Log.w(TAG, "own device discovery failed", it)
                emptyList()
            }
        val targets = cryptoManager.planSend(
            recipientDeviceIds = recipientBundles.map { it.deviceId },
            ownDeviceIds = ownBundles.map { it.deviceId },
            ourDeviceId = ourDeviceId,
            recipientIsSelf = contactId == myId,
            primarySendCovered = primary.deviceId,
        )
        if (targets.isEmpty()) return

        val bundlesByDevice = (recipientBundles + ownBundles).associateBy { it.deviceId }
        for (target in targets) {
            val accountId = if (target.audience == DeliveryAudience.RECIPIENT) contactId else myId
            val bundle = bundlesByDevice[target.deviceId]
            val peer = runCatching {
                sessionManager.ensureSessionForDevice(accountId, target.deviceId)
            }.getOrElse {
                Log.w(TAG, "fan-out session init failed ${target.deviceId.take(8)}…", it)
                continue
            }
            val identity = bundle?.identityPublic ?: peer.identityPublic
            if (identity.isEmpty()) {
                Log.w(TAG, "fan-out identity missing ${target.deviceId.take(8)}…")
                continue
            }
            val tag = runCatching {
                cryptoManager.deviceCopyTag(baseMessageId, target.deviceId, identity)
            }.getOrElse {
                Log.w(TAG, "fan-out tag failed ${target.deviceId.take(8)}…", it)
                continue
            }
            val isOwnReplica = target.audience == DeliveryAudience.OWN_REPLICA
            val wireMessageId = baseMessageId + if (isOwnReplica) "-ss-$tag" else "-fd-$tag"
            val routedPlaintext = if (isOwnReplica) {
                // SENDER_SYNC is the outer envelope type. Its encrypted body remains the same
                // user-message KNST frame (type=1), prefixed with SSR1 for post-decrypt routing.
                SenderSyncRouting.encode(contactId, plaintext)
            } else {
                plaintext
            }
            val actions = orchestrator.handleEvent(
                CfeIncomingEvent.OutgoingMessage(
                    contactId = target.deviceId,
                    messageId = wireMessageId,
                    plaintext = routedPlaintext,
                    contentType = 0u,
                ),
            )
            persistSessionActions(actions)
            val encrypted = actions.filterIsInstance<CfeAction.SendEncryptedMessage>()
                .firstOrNull { it.to == target.deviceId }
                ?.payload
                ?: run {
                    Log.w(TAG, "fan-out core returned no ciphertext ${target.deviceId.take(8)}…")
                    continue
                }
            val result = if (isOwnReplica) {
                messagingService.sendMessage(
                    messageId = wireMessageId,
                    senderId = myId,
                    recipientId = myId,
                    conversationId = "",
                    encryptedPayload = encrypted,
                    timestampMs = timestampMs,
                    contentType = ContentType.CONTENT_TYPE_SENDER_SYNC,
                )
            } else {
                sendRecipientCopy(
                    messageId = wireMessageId,
                    senderId = myId,
                    recipientId = contactId,
                    timestampMs = timestampMs,
                    encryptedPayload = encrypted,
                    identityPublic = identity,
                )
            }
            if (!result.success) {
                Log.w(TAG, "fan-out rejected ${target.deviceId.take(8)}… ${result.errorCode}")
            }
        }
    }

    private suspend fun sendRecipientCopy(
        messageId: String,
        senderId: String,
        recipientId: String,
        timestampMs: Long,
        encryptedPayload: ByteArray,
        identityPublic: ByteArray,
    ): MessagingService.SendResult {
        return if (stealthPolicy.shouldUseSealedSender()) {
            val sealed = stealthSender.buildSealedInner(
                recipientUserId = recipientId,
                recipientIdentityKey = identityPublic,
                encryptedPayload = encryptedPayload,
                contentType = ContentType.CONTENT_TYPE_UNSPECIFIED,
            )
            if (MessagingService.SEALED_UNAUTHENTICATED_TRANSPORT) {
                messagingService.sendSealedMessage(sealed)
            } else {
                messagingService.sendMessage(
                    messageId = messageId,
                    senderId = senderId,
                    recipientId = recipientId,
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
                senderId = senderId,
                recipientId = recipientId,
                conversationId = "",
                encryptedPayload = encryptedPayload,
                timestampMs = timestampMs,
                contentType = ContentType.CONTENT_TYPE_E2EE_SIGNAL,
            )
        }
    }

    private fun knstText(text: String, messageId: String): ByteArray {
        val payload = MessageContent.newBuilder()
            .setText(TextMessage.newBuilder().setText(text))
            .build()
            .toByteArray()
        val uuid = runCatching { UUID.fromString(messageId) }.getOrElse { UUID.randomUUID() }
        return KnstFrame.pack(payload, KnstFrame.TYPE_E2EE_SIGNAL, uuid)
    }

    private companion object {
        const val TAG = "SendMessageUseCase"
        const val MAX_ATTEMPTS = 3
        const val BACKOFF_MS = 400L
    }
}
