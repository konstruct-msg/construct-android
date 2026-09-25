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
import com.construct.messenger.data.model.ReplyRef
import com.construct.messenger.service.OrchestratorGateway
import com.construct.messenger.service.SessionManager
import com.construct.messenger.stealth.StealthPolicy
import com.construct.messenger.stealth.StealthSenderService
import com.construct.messenger.util.ConversationId
import com.construct.messenger.util.DisplayNameGenerator
import com.construct.messenger.util.KnstFrame
import com.construct.messenger.util.TextWire
import com.construct.messenger.util.SenderSyncRouting
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import shared.proto.core.v1.EnvelopeOuterClass.ContentType
import uniffi.construct_core.CfeAction
import uniffi.construct_core.CfeIncomingEvent
import uniffi.construct_core.DeliveryAudience

sealed interface SendOutcome {
    data class Sent(val messageId: String) : SendOutcome
    data class Failed(val messageId: String, val reason: String) : SendOutcome
}

/**
 * 1:1 text send — one copy per device of the recipient, and no privileged one.
 *
 * **Canon:** iOS `OutboundMessagePipeline.sendToRecipientDevices` (`construct-messenger@4a74c013`).
 *
 * 1. Optimistic Room row (SENDING).
 * 2. `MessageContent` proto → KNST frame (type in byte 5). A reply is a `QuotedMessage`
 *    on that text — id and a 200-character preview — and is not a field of the envelope.
 * 3. Ensure a Double-Ratchet session with the pinned device (prekey fetch is destructive — only
 *    if missing). This is establishment, not addressing: the set below is what the send reaches.
 * 4. The recipient's device set — the registry, corrected by the directory — handed to the core's
 *    `plan_send` with nothing marked as already covered.
 * 5. Per device: CFE `OutgoingMessage` → `SendEncryptedMessage`. The session blob is persisted
 *    **before** the unary send (sender-state-durability-before-send).
 * 6. Per device: sealed to *that* device's identity key, named `<base>-fd-<tag>`, retried on a
 *    retryable refusal. Fail closed — never identified-downgrade when stealth is on.
 * 7. The row's status is a fold: SENT once any copy is accepted, FAILED when none is.
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
    suspend operator fun invoke(contactId: String, text: String, reply: ReplyRef? = null): SendOutcome {
        val body = text.trim()
        require(body.isNotEmpty()) { "empty message" }

        val myId = keystoreManager.getUserId()
            ?: return SendOutcome.Failed("", "not authenticated")
        require(cryptoManager.isMessagingReady) { "orchestrator not ready" }

        val messageId = UUID.randomUUID().toString().lowercase()
        val timestampMs = System.currentTimeMillis()
        val chatId = ConversationId.direct(myId, contactId)

        persistOutgoing(chatId, contactId, messageId, body, timestampMs, DeliveryStatus.SENDING, reply)

        return try {
            val plaintext = knstText(body, messageId, reply)
            // Establishment is deliberately unchanged by this: the first send to a peer we hold
            // nothing with still opens a session with the device the registry pins, and that
            // device is also the offline answer for the set below when the key server cannot be
            // reached. Which devices a send must hold sessions with is the machine's question,
            // not this one's.
            val pinned = sessionManager.ensureSession(contactId)
            if (contactId == myId) {
                return sendNoteToSelf(myId, messageId, timestampMs, plaintext, pinned)
            }
            val tally = deliverCopies(myId, contactId, messageId, timestampMs, plaintext, pinned)
            if (tally.recipientAccepted > 0) {
                // `sent` has always meant "in the person's mailbox", and one accepted copy puts
                // it there. Devices that refused are named in the log, not in the row — a per
                // device status needs a per device carrier, which Room does not have yet.
                messageDao.updateDeliveryStatus(messageId, DeliveryStatus.SENT.name)
                SendOutcome.Sent(messageId)
            } else {
                messageDao.updateDeliveryStatus(messageId, DeliveryStatus.FAILED.name)
                SendOutcome.Failed(messageId, tally.lastError)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "send failed ${messageId.take(8)}…", e)
            messageDao.updateDeliveryStatus(messageId, DeliveryStatus.FAILED.name)
            SendOutcome.Failed(messageId, e.message ?: "send failed")
        }
    }

    /**
     * A note to self, unchanged.
     *
     * `plan_send` returns no recipient targets when the recipient is us — the recipient's devices
     * *are* our devices, and planning both audiences would send every replica two copies. So the
     * account-addressed copy here is not a privileged one among device copies; it is the only
     * thing that puts the message in our own mailbox, and removing it would leave a single-device
     * user's notes in Room and nowhere else. Whether that copy should exist at all is a question
     * about history sync, not about a peer's device set.
     */
    private suspend fun sendNoteToSelf(
        myId: String,
        messageId: String,
        timestampMs: Long,
        plaintext: ByteArray,
        pinned: SessionManager.SessionPeer,
    ): SendOutcome {
        val encrypted = encryptFor(pinned.deviceId, messageId, plaintext)
            ?: return SendOutcome.Failed(messageId, "no ciphertext")
        val result = sendOneCopy(
            myId = myId,
            accountId = myId,
            wireMessageId = messageId,
            timestampMs = timestampMs,
            encrypted = encrypted,
            identityPublic = pinned.identityPublic,
            isOwnReplica = false,
        )
        if (result?.success != true) {
            messageDao.updateDeliveryStatus(messageId, DeliveryStatus.FAILED.name)
            return SendOutcome.Failed(messageId, result?.errorCode?.ifEmpty { "send failed" } ?: "send failed")
        }
        messageDao.updateDeliveryStatus(messageId, DeliveryStatus.SENT.name)
        runCatching {
            deliverCopies(myId, myId, messageId, timestampMs, plaintext, pinned)
        }.onFailure {
            Log.w(TAG, "replica fan-out failed ${messageId.take(8)}…", it)
        }
        return SendOutcome.Sent(messageId)
    }

    private data class DeliveryTally(
        val recipientAccepted: Int,
        val replicaAccepted: Int,
        val lastError: String,
    )

    /**
     * One copy per device, through one sender.
     *
     * §B item 1 of `decisions/a-peer-is-a-set-of-devices`, the Android twin of the iOS change
     * `construct-messenger@4a74c013`. Until now a message reached one of the recipient's devices
     * by an ordinary send — addressed to the account, sealed to the pinned key, retried, its
     * answer the row's status — and every other device by a fan-out with no retry, no status and
     * a different naming. The second device of anyone was a second-class recipient.
     *
     * Every recipient copy now looks the same: named `<base>-fd-<tag>`, sealed to *that* device's
     * identity key, retried the same way. Nothing is privileged, so nothing can be already
     * covered — `plan_send` lost the parameter that used to say so, once this was the last caller
     * relying on it.
     */
    private suspend fun deliverCopies(
        myId: String,
        contactId: String,
        baseMessageId: String,
        timestampMs: Long,
        plaintext: ByteArray,
        pinned: SessionManager.SessionPeer,
    ): DeliveryTally {
        val ourDeviceId = cryptoManager.currentDeviceId()
            ?: return DeliveryTally(0, 0, "no device id")
        val recipientIsSelf = contactId == myId
        val recipientDevices =
            if (recipientIsSelf) emptyList() else recipientDeviceSet(contactId, pinned)
        val ownBundles = runCatching { sessionManager.discoverOwnDeviceBundles(myId) }
            .getOrElse {
                Log.w(TAG, "own device discovery failed", it)
                emptyList()
            }

        val targets = cryptoManager.planSend(
            recipientDeviceIds = recipientDevices.map { it.deviceId },
            ownDeviceIds = ownBundles.map { it.deviceId },
            ourDeviceId = ourDeviceId,
            recipientIsSelf = recipientIsSelf,
        )
        if (targets.isEmpty()) return DeliveryTally(0, 0, "no device to send to")

        val identityByDevice = HashMap<String, ByteArray>()
        recipientDevices.forEach { identityByDevice[it.deviceId] = it.identityPublic }
        ownBundles.forEach { identityByDevice[it.deviceId] = it.identityPublic }

        var recipientAccepted = 0
        var replicaAccepted = 0
        var lastError = "send failed"
        for (target in targets) {
            val isOwnReplica = target.audience == DeliveryAudience.OWN_REPLICA
            val accountId = if (isOwnReplica) myId else contactId
            val peer = runCatching {
                sessionManager.ensureSessionForDevice(accountId, target.deviceId)
            }.getOrElse {
                Log.w(TAG, "session init failed ${target.deviceId.take(8)}…", it)
                lastError = "session init failed"
                continue
            }
            val identity = identityByDevice[target.deviceId] ?: peer.identityPublic
            if (identity.isEmpty()) {
                // Fail closed rather than seal to nothing: with stealth on an empty key is an
                // identified downgrade wearing a seal's name, and with stealth off it is a copy
                // the named device could never verify.
                Log.w(TAG, "identity missing ${target.deviceId.take(8)}… — copy skipped")
                lastError = "no identity key"
                continue
            }
            val tag = runCatching {
                cryptoManager.deviceCopyTag(baseMessageId, target.deviceId, identity)
            }.getOrElse {
                Log.w(TAG, "copy tag failed ${target.deviceId.take(8)}…", it)
                lastError = "tag failed"
                continue
            }
            val wireMessageId = baseMessageId + if (isOwnReplica) "-ss-$tag" else "-fd-$tag"
            val routedPlaintext = if (isOwnReplica) {
                // SENDER_SYNC is the outer envelope type. Its encrypted body remains the same
                // user-message KNST frame (type=1), prefixed with SSR1 for post-decrypt routing.
                SenderSyncRouting.encode(contactId, plaintext)
            } else {
                plaintext
            }
            val encrypted = encryptFor(target.deviceId, wireMessageId, routedPlaintext)
            if (encrypted == null) {
                lastError = "no ciphertext"
                continue
            }
            val result = sendOneCopy(
                myId = myId,
                accountId = accountId,
                wireMessageId = wireMessageId,
                timestampMs = timestampMs,
                encrypted = encrypted,
                identityPublic = identity,
                isOwnReplica = isOwnReplica,
            )
            if (result?.success == true) {
                if (isOwnReplica) replicaAccepted++ else recipientAccepted++
            } else {
                lastError = result?.errorCode?.ifEmpty { "send failed" } ?: "send failed"
                Log.w(TAG, "copy rejected ${target.deviceId.take(8)}… $lastError")
            }
        }
        return DeliveryTally(recipientAccepted, replicaAccepted, lastError)
    }

    /**
     * The devices of [contactId] this send must reach.
     *
     * The registry answers first so a send to someone we already hold sessions with never waits
     * on the key server, and the directory only corrects it — a device the account has since
     * added appears, a key that rotated wins. The pinned device is the *offline* answer and only
     * that: adding it unconditionally would resurrect a device the directory deliberately
     * dropped.
     */
    private suspend fun recipientDeviceSet(
        contactId: String,
        pinned: SessionManager.SessionPeer,
    ): List<SessionManager.SessionPeer> {
        val merged = LinkedHashMap<String, SessionManager.SessionPeer>()
        sessionManager.knownPeerDevices(contactId).forEach { merged[it.deviceId] = it }
        runCatching { sessionManager.discoverPeerBundles(contactId) }
            .getOrElse {
                Log.w(TAG, "recipient device discovery failed ${contactId.take(8)}…", it)
                emptyList()
            }
            .forEach {
                merged[it.deviceId] = SessionManager.SessionPeer(
                    accountId = it.accountId,
                    deviceId = it.deviceId,
                    identityPublic = it.identityPublic,
                )
            }
        if (merged.isEmpty()) merged[pinned.deviceId] = pinned
        return merged.values.toList()
    }

    /**
     * Encrypt one copy for one device. The session blob is persisted **before** the caller is
     * allowed to send it — see `decisions/sender-state-durability-before-send`: a ciphertext
     * released over an advance a crash can roll back reuses a message number, and healing
     * (msgNum==0 only) does not cover a mid-session desync.
     */
    private suspend fun encryptFor(
        deviceId: String,
        wireMessageId: String,
        plaintext: ByteArray,
    ): ByteArray? {
        val actions = orchestrator.handleEvent(
            CfeIncomingEvent.OutgoingMessage(
                contactId = deviceId,
                messageId = wireMessageId,
                plaintext = plaintext,
                contentType = 0u,
            ),
        )
        persistSessionActions(actions)
        val payload = actions.filterIsInstance<CfeAction.SendEncryptedMessage>()
            .firstOrNull { it.to == deviceId }
            ?.payload
        if (payload == null) Log.w(TAG, "core returned no ciphertext ${deviceId.take(8)}…")
        return payload
    }

    /**
     * Send one copy, with the retry budget every copy now gets. `null` means the transport threw
     * on the last attempt; a returned result may still carry `success = false`.
     */
    private suspend fun sendOneCopy(
        myId: String,
        accountId: String,
        wireMessageId: String,
        timestampMs: Long,
        encrypted: ByteArray,
        identityPublic: ByteArray,
        isOwnReplica: Boolean,
    ): MessagingService.SendResult? {
        var last: MessagingService.SendResult? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            val result = try {
                if (isOwnReplica) {
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
                        recipientId = accountId,
                        timestampMs = timestampMs,
                        encryptedPayload = encrypted,
                        identityPublic = identityPublic,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (attempt == MAX_ATTEMPTS - 1) {
                    Log.w(TAG, "copy send threw ${wireMessageId.takeLast(16)}", e)
                    return last
                }
                delay(BACKOFF_MS * (attempt + 1))
                return@repeat
            }
            last = result
            if (result.success) return result
            if (!result.retryable || attempt == MAX_ATTEMPTS - 1) return result
            delay(result.retryAfterMs.coerceAtLeast(BACKOFF_MS) * (attempt + 1))
        }
        return last
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
        reply: ReplyRef?,
    ) {
        messageDao.insert(
            MessageEntity(
                id = messageId,
                chatId = chatId,
                text = text,
                isSentByMe = true,
                timestamp = timestampMs,
                deliveryStatus = status.name,
                replyToId = reply?.messageId,
                replyPreview = reply?.preview?.ifEmpty { null },
                replyMediaType = reply?.mediaType,
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

    private fun knstText(text: String, messageId: String, reply: ReplyRef?): ByteArray {
        val payload = TextWire.encode(text, reply)
        val uuid = runCatching { UUID.fromString(messageId) }.getOrElse { UUID.randomUUID() }
        return KnstFrame.pack(payload, KnstFrame.TYPE_E2EE_SIGNAL, uuid)
    }

    private companion object {
        const val TAG = "SendMessageUseCase"
        const val MAX_ATTEMPTS = 3
        const val BACKOFF_MS = 400L
    }
}
