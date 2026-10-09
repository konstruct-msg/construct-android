package com.construct.messenger.domain.usecase

import com.construct.messenger.diagnostics.Log
import com.construct.messenger.crypto.CryptoManager
import com.construct.messenger.data.api.MessagingService
import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.data.local.SessionStateStore
import com.construct.messenger.data.local.db.ChatDao
import com.construct.messenger.data.local.db.ChatEntity
import com.construct.messenger.data.local.db.MessageDao
import com.construct.messenger.data.local.db.MessageEntity
import com.construct.messenger.data.local.ContactStore
import com.construct.messenger.data.model.DeliveryStatus
import com.construct.messenger.data.model.ReplyRef
import com.construct.messenger.service.MediaPreviewText
import com.construct.messenger.service.OrchestratorGateway
import com.construct.messenger.service.ServerMessageIds
import com.construct.messenger.service.SessionManager
import com.construct.messenger.stealth.OwnDeviceCopy
import com.construct.messenger.stealth.StealthPolicy
import com.construct.messenger.stealth.SealedEnvelopeType
import com.construct.messenger.stealth.SealedSend
import com.construct.messenger.stealth.StealthSenderService
import com.construct.messenger.util.ConversationId
import com.construct.messenger.data.local.db.refreshChatPreview
import com.construct.messenger.data.local.db.applyEdit
import com.construct.messenger.util.EditWire
import com.construct.messenger.util.KnstFrame
import com.construct.messenger.util.MediaWire
import com.construct.messenger.util.TextWire
import com.construct.messenger.util.SenderSyncRouting
import java.util.UUID
import kotlin.random.Random
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
/** What [SendMessageUseCase.resend] did: [FAILED] is worth another try, [NOT_OURS] is not. */
enum class ResendOutcome { SENT, NOT_OURS, FAILED }

class SendMessageUseCase @Inject constructor(
    private val keystoreManager: KeystoreManager,
    private val sessionManager: SessionManager,
    private val orchestrator: OrchestratorGateway,
    private val cryptoManager: CryptoManager,
    private val messagingService: MessagingService,
    private val stealthPolicy: StealthPolicy,
    private val stealthSender: StealthSenderService,
    private val sealedSend: SealedSend,
    private val messageDao: MessageDao,
    private val chatDao: ChatDao,
    private val contacts: ContactStore,
    private val sessionStateStore: SessionStateStore,
    private val serverMessageIds: ServerMessageIds,
    private val mediaPreview: MediaPreviewText,
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
        return deliver(myId, contactId, messageId, timestampMs, TextWire.encode(body, reply))
    }

    /**
     * A call signal (iOS `CallManager.sendCallSignalProto`): [frame] — a `WebRTCSignal` in a type-12
     * KNST frame — through the core's `OutgoingCallSignal` to the peer's **pinned** device, then
     * the same send as a message: sealed while stealth is on and never sent identified then, the
     * same retries. To the pinned device only, as on iOS: a person's second device does not ring
     * (`decisions/a-peer-is-a-set-of-devices.md` — that is a decision of its own).
     *
     * True when the server took it. No row is written: a signal is not a message.
     */
    suspend fun sendCallSignal(peerAccountId: String, messageId: String, frame: ByteArray): Boolean {
        val myId = keystoreManager.getUserId() ?: return false
        if (!cryptoManager.isMessagingReady) return false
        return try {
            val pinned = sessionManager.ensureSession(peerAccountId)
            val actions = orchestrator.handleEvent(CfeIncomingEvent.OutgoingCallSignal(pinned.deviceId, messageId, frame))
            persistSessionActions(actions)
            actions.filterIsInstance<CfeAction.NotifyError>().forEach { Log.w(TAG, "call signal: core [${it.code}] ${it.message}") }
            val payload = actions.filterIsInstance<CfeAction.SendEncryptedMessage>().firstOrNull { it.to == pinned.deviceId }?.payload
                ?: return false
            val result = sendOneCopy(
                myId = myId,
                accountId = peerAccountId,
                wireMessageId = messageId,
                timestampMs = System.currentTimeMillis(),
                encrypted = payload,
                identityPublic = pinned.identityPublic,
                isOwnReplica = false,
            )
            result?.success == true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "call signal ${messageId.take(8)}… to ${peerAccountId.take(8)}… failed", e)
            false
        }
    }

    /**
     * A sticker: `MessageContent.sticker`, a reference of about 40 bytes inside the ciphertext —
     * no upload, the ordinary text path (iOS `ChatSendCoordinator.sendSticker`). No quote travels
     * with it: `StickerRef` has no field for one, on iOS either.
     */
    suspend fun sendSticker(contactId: String, ref: com.construct.messenger.stickers.StickerReference): SendOutcome {
        val myId = keystoreManager.getUserId() ?: return SendOutcome.Failed("", "not authenticated")
        if (!cryptoManager.isMessagingReady) return SendOutcome.Failed("", "orchestrator not ready")
        val messageId = UUID.randomUUID().toString().lowercase()
        val timestampMs = System.currentTimeMillis()
        val chatId = ConversationId.direct(myId, contactId)
        persistOutgoing(chatId, contactId, messageId, "", timestampMs, DeliveryStatus.SENDING, null, MediaWire.sticker(ref))
        val content = shared.proto.messaging.v1.Content.MessageContent.newBuilder().setSticker(ref.toWire()).build().toByteArray()
        return deliver(myId, contactId, messageId, timestampMs, content)
    }

    /**
     * An outgoing message with media, shown before it is sent: the row the bubble reads, in
     * SENDING, while its media uploads (`SendMediaUseCase`). [media] names the staged copies.
     */
    suspend fun persistMedia(contactId: String, messageId: String, timestampMs: Long, media: MediaWire.Stored, reply: ReplyRef?) {
        val myId = keystoreManager.getUserId() ?: error("not authenticated")
        persistOutgoing(ConversationId.direct(myId, contactId), contactId, messageId, media.caption, timestampMs, DeliveryStatus.SENDING, reply, media)
    }

    /** The uploaded media, by the ids the store gave it, in place of the staged ones. */
    suspend fun replaceMedia(messageId: String, media: MediaWire.Stored) {
        val row = messageDao.getById(messageId) ?: return
        messageDao.insert(row.copy(mediaType = media.kind, mediaPayload = media.bytes))
    }

    suspend fun markFailed(messageId: String) {
        messageDao.updateDeliveryStatus(messageId, DeliveryStatus.FAILED.name)
    }

    suspend fun markSending(messageId: String) {
        messageDao.updateDeliveryStatus(messageId, DeliveryStatus.SENDING.name)
    }

    /** One of our messages in the chat with [contactId] that no device took — what Retry may send. */
    suspend fun failedRow(contactId: String, messageId: String): MessageEntity? {
        val myId = keystoreManager.getUserId() ?: return null
        val row = messageDao.getByIdIgnoreCase(messageId) ?: return null
        return row.takeIf {
            it.isSentByMe && it.contentType == 0 && it.chatId == ConversationId.direct(myId, contactId) &&
                it.deliveryStatus == DeliveryStatus.FAILED.name
        }
    }

    /**
     * Retry ([failedRow]): the same message, under the same id, sent again — iOS
     * `MessageRetryManager`. Its text with its quote, or its media once uploaded.
     */
    suspend fun retry(contactId: String, row: MessageEntity): SendOutcome {
        val content = resendableContent(row) ?: return SendOutcome.Failed(row.id, "nothing to send")
        messageDao.updateDeliveryStatus(row.id, DeliveryStatus.SENDING.name)
        return deliverPrepared(contactId, row.id, row.timestamp, content)
    }

    /** Send [content] as the row [messageId] already written; its status follows the answer. */
    suspend fun deliverPrepared(contactId: String, messageId: String, timestampMs: Long, content: ByteArray): SendOutcome {
        val myId = keystoreManager.getUserId() ?: return SendOutcome.Failed(messageId, "not authenticated")
        if (!cryptoManager.isMessagingReady) return SendOutcome.Failed(messageId, "orchestrator not ready")
        return deliver(myId, contactId, messageId, timestampMs, content)
    }

    private suspend fun deliver(
        myId: String,
        contactId: String,
        messageId: String,
        timestampMs: Long,
        content: ByteArray,
    ): SendOutcome {
        return try {
            // Establishment is deliberately unchanged by this: the first send to a peer we hold
            // nothing with still opens a session with the device the registry pins, and that
            // device is also the offline answer for the set below when the key server cannot be
            // reached. Which devices a send must hold sessions with is the machine's question,
            // not this one's.
            val pinned = sessionManager.ensureSession(contactId)
            if (contactId == myId) {
                return sendNoteToSelf(myId, messageId, timestampMs, content, pinned)
            }
            val tally = deliverCopies(myId, contactId, messageId, timestampMs, content, pinned)
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
     * Send one of our own messages again, to one device of [contactId] — the answer to the core's
     * `ResendMessage`: that device could not read [messageId] (a DECRYPTION_ERROR named it). If
     * the core retired our state with the device, the copy opens a new one on the way.
     *
     * The same per-device path as a send, and only to [deviceId]: the account's other devices
     * read their copies. Text, and media from the wire message its row keeps — iOS resends text
     * only, keeping no plaintext of media. Until
     * 2026-09-27 nothing on Android resent anything; a message lost to a broken session stayed
     * lost (`decisions/sessions-renew-by-sending.md`). Canon: iOS
     * `SessionCoordinator.resendAfterDecryptionError`.
     */
    suspend fun resend(contactId: String, deviceId: String, messageId: String): ResendOutcome {
        val myId = keystoreManager.getUserId() ?: return ResendOutcome.FAILED
        // The peer names the id it received, which for a sealed copy is the server's.
        val localId = serverMessageIds.localId(messageId)
        val row = messageDao.getById(localId) ?: messageDao.getByIdIgnoreCase(localId)
        val content = row?.let(::resendableContent)
        if (row == null || !row.isSentByMe || row.contentType != 0 || content == null ||
            row.chatId != ConversationId.direct(myId, contactId)
        ) {
            Log.i(TAG, "resend ${messageId.take(8)}… for ${deviceId.take(8)}… — no message of ours to resend here")
            return ResendOutcome.NOT_OURS
        }
        return try {
            val peer = sessionManager.ensureSessionForDevice(contactId, deviceId)
            val tag = cryptoManager.deviceCopyTag(row.id, deviceId, peer.identityPublic)
            val result = sendFrames(
                frames = framesOf(content, row.id, isOwnReplica = false, partner = contactId),
                wireIdBase = "${row.id}-fd-$tag",
                deviceId = deviceId,
                myId = myId,
                accountId = contactId,
                localId = row.id,
                timestampMs = row.timestamp,
                identityPublic = peer.identityPublic,
                isOwnReplica = false,
            )
            val sent = result?.success == true
            Log.i(TAG, "resend ${messageId.take(8)}… to ${deviceId.take(8)}… — ${if (sent) "sent" else "failed"}")
            if (sent) ResendOutcome.SENT else ResendOutcome.FAILED
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "resend ${messageId.take(8)}… to ${deviceId.take(8)}… threw", e)
            ResendOutcome.FAILED
        }
    }

    /**
     * What [row] was sent as, again: its text with its quote, or its media — which iOS cannot
     * resend, keeping no plaintext of it; the row here holds the wire message. Null when there is
     * nothing to send, or the media never finished uploading.
     */
    private fun resendableContent(row: MessageEntity): ByteArray? {
        val reply = row.replyToId?.let { ReplyRef(it, row.replyPreview.orEmpty(), row.replyMediaType) }
        val payload = row.mediaPayload
        if (row.mediaType != null && payload != null) {
            val media = MediaWire.decode(row.mediaType, payload) ?: return null
            if (MediaWire.isStaged(media)) return null
            return MediaWire.content(row.mediaType, payload)
        }
        return row.text.takeIf { it.isNotEmpty() }?.let { TextWire.encode(it, reply) }
    }

    /**
     * Our profile to every device of [contactId] (iOS `ProfileShareViewModel.deliver`): [payload],
     * a `ProfileShare`, as a content-type-29 KNST frame under a fresh id, per device, as a message
     * is — the type lives inside the ciphertext; the envelope stays generic. Not to our own devices,
     * as on iOS: it is about us, they already know. No row is written. True when a device of theirs
     * took it.
     */
    suspend fun shareProfile(contactId: String, payload: ByteArray): Boolean {
        val myId = keystoreManager.getUserId() ?: return false
        if (!cryptoManager.isMessagingReady || contactId == myId) return false
        val id = UUID.randomUUID()
        return try {
            val pinned = sessionManager.ensureSession(contactId)
            val tally = deliverCopies(
                myId, contactId, id.toString(), System.currentTimeMillis(), payload, pinned,
                recipientsOnly = true, contentType = ContentType.CONTENT_TYPE_PROFILE_VALUE,
            )
            Log.i(TAG, "profile to ${contactId.take(8)}… — ${tally.recipientAccepted} device(s) took it")
            tally.recipientAccepted > 0
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "profile to ${contactId.take(8)}… failed", e)
            false
        }
    }

    /**
     * Edit one of our own text messages, or a photo's caption. Same fan-out as a send, different payload:
     * `MessageContent.edit` names the row, and no new row is written. The local
     * text changes only after a recipient copy is accepted — a failure leaves the
     * original in place, as on iOS.
     */
    suspend fun edit(contactId: String, targetMessageId: String, newText: String): SendOutcome {
        val body = newText.trim()
        if (body.isEmpty()) return SendOutcome.Failed(targetMessageId, "empty message")
        val myId = keystoreManager.getUserId()
            ?: return SendOutcome.Failed(targetMessageId, "not authenticated")
        if (!cryptoManager.isMessagingReady) {
            return SendOutcome.Failed(targetMessageId, "orchestrator not ready")
        }
        val row = messageDao.getByIdIgnoreCase(targetMessageId)
            ?: return SendOutcome.Failed(targetMessageId, "missing")
        if (!row.isSentByMe) return SendOutcome.Failed(targetMessageId, "not author")

        return when (val outcome = sendAction(myId, contactId, EditWire.encode(row.id, body), "edit")) {
            is SendOutcome.Sent -> {
                messageDao.applyEdit(row, body)
                refreshChatPreview(chatDao, messageDao, row.chatId)
                outcome
            }
            is SendOutcome.Failed -> SendOutcome.Failed(targetMessageId, outcome.reason)
        }
    }

    /**
     * A reaction: `MessageContent.reaction` ([ReactionWire]) through the same fan-out as an edit,
     * our own devices included. No row is written here — the caller has already applied it to the
     * reaction store and puts the old one back on a failure.
     */
    suspend fun react(contactId: String, content: ByteArray): SendOutcome {
        val myId = keystoreManager.getUserId() ?: return SendOutcome.Failed("", "not authenticated")
        if (!cryptoManager.isMessagingReady) return SendOutcome.Failed("", "orchestrator not ready")
        return sendAction(myId, contactId, content, "reaction")
    }

    /**
     * Something said about a message already sent (an edit, a reaction), under a fresh action id.
     * Sent when a recipient device took it — or, for a note to self, when the mailbox did.
     */
    private suspend fun sendAction(myId: String, contactId: String, content: ByteArray, label: String): SendOutcome {
        val actionId = UUID.randomUUID().toString().lowercase()
        val timestampMs = System.currentTimeMillis()
        return try {
            val pinned = sessionManager.ensureSession(contactId)
            if (contactId == myId) {
                val note = sendNoteToSelf(myId, actionId, timestampMs, content, pinned)
                if (note is SendOutcome.Failed) return note
            } else {
                val tally = deliverCopies(myId, contactId, actionId, timestampMs, content, pinned)
                if (tally.recipientAccepted == 0) return SendOutcome.Failed(actionId, tally.lastError)
            }
            SendOutcome.Sent(actionId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "$label failed ${actionId.take(8)}…", e)
            SendOutcome.Failed(actionId, e.message ?: "$label failed")
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
        content: ByteArray,
        pinned: SessionManager.SessionPeer,
    ): SendOutcome {
        val result = sendFrames(
            frames = framesOf(content, messageId, isOwnReplica = false, partner = myId),
            wireIdBase = messageId,
            deviceId = pinned.deviceId,
            myId = myId,
            accountId = myId,
            localId = null,
            timestampMs = timestampMs,
            identityPublic = pinned.identityPublic,
            isOwnReplica = false,
        )
        if (result?.success != true) {
            messageDao.updateDeliveryStatus(messageId, DeliveryStatus.FAILED.name)
            return SendOutcome.Failed(messageId, result?.errorCode?.ifEmpty { "send failed" } ?: "send failed")
        }
        messageDao.updateDeliveryStatus(messageId, DeliveryStatus.SENT.name)
        runCatching {
            deliverCopies(myId, myId, messageId, timestampMs, content, pinned)
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
        content: ByteArray,
        pinned: SessionManager.SessionPeer,
        recipientsOnly: Boolean = false,
        /** What a recipient's frames say they carry; an ordinary message is type 1. */
        contentType: Int = KnstFrame.TYPE_E2EE_SIGNAL,
    ): DeliveryTally {
        val ourDeviceId = cryptoManager.currentDeviceId()
            ?: return DeliveryTally(0, 0, "no device id")
        val recipientIsSelf = contactId == myId
        val recipientDevices =
            if (recipientIsSelf) emptyList() else recipientDeviceSet(contactId, pinned)
        val ownBundles = if (recipientsOnly) {
            emptyList()
        } else {
            runCatching { sessionManager.discoverOwnDeviceBundles(myId) }
                .getOrElse {
                    Log.w(TAG, "own device discovery failed", it)
                    emptyList()
                }
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
            val result = sendFrames(
                frames = framesOf(content, baseMessageId, isOwnReplica, partner = contactId, contentType),
                wireIdBase = baseMessageId + if (isOwnReplica) "-ss-$tag" else "-fd-$tag",
                deviceId = target.deviceId,
                myId = myId,
                accountId = accountId,
                localId = if (isOwnReplica) null else baseMessageId,
                timestampMs = timestampMs,
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
        // A copy to a sibling carries our certificate beside the wire payload: it goes unsealed,
        // and a sibling's first copy opens its session from nothing else. Every copy, not only
        // the first — whether the sibling still holds this session is its knowledge, not ours.
        val ownCopy = if (isOwnReplica) {
            val certificate = runCatching { stealthSender.getSenderCertificate() }
                .onFailure { Log.w(TAG, "no sender certificate for ${wireMessageId.takeLast(16)} — a first copy will not open", it) }
                .getOrNull()
            OwnDeviceCopy.wrap(certificate, encrypted)
        } else {
            null
        }
        var last: MessagingService.SendResult? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            val result = try {
                if (ownCopy != null) {
                    messagingService.sendMessage(
                        messageId = wireMessageId,
                        senderId = myId,
                        recipientId = myId,
                        conversationId = "",
                        encryptedPayload = ownCopy,
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
        media: MediaWire.Stored? = null,
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
                mediaType = media?.kind,
                mediaPayload = media?.bytes,
            ),
        )
        val preview = media?.let(mediaPreview::of) ?: text
        val existing = chatDao.getById(chatId)
        if (existing == null) {
            chatDao.upsert(
                ChatEntity(
                    id = chatId,
                    otherUserId = contactId,
                    lastMessageText = preview,
                    lastMessageTime = timestampMs,
                    unreadCount = 0,
                ),
            )
        } else {
            chatDao.updateLastMessage(chatId, preview, timestampMs)
        }
        contacts.ensure(contactId)
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
            sealedSend.send(
                recipientUserId = recipientId,
                recipientIdentityKey = identityPublic,
                encryptedPayload = encryptedPayload,
                contentType = SealedEnvelopeType.GENERIC,
            )
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

    /**
     * [content] as the frames one device receives. A recipient gets type-1 frames of the content;
     * our own device gets type-23 frames of `SSR1 ‖ content` — iOS `MultiDeviceSendCoordinator`:
     * the partner goes on before chunking, so a long copy carries it once. Until 2026-09-30
     * Android put `SSR1` outside the frame, and iOS siblings could not read those copies.
     */
    private fun framesOf(
        content: ByteArray,
        messageId: String,
        isOwnReplica: Boolean,
        partner: String,
        contentType: Int = KnstFrame.TYPE_E2EE_SIGNAL,
    ): List<ByteArray> {
        val uuid = runCatching { UUID.fromString(messageId) }.getOrElse { UUID.randomUUID() }
        return if (isOwnReplica) {
            KnstFrame.chunks(SenderSyncRouting.encode(partner, content), KnstFrame.TYPE_SENDER_SYNC, uuid)
        } else {
            KnstFrame.chunks(content, contentType, uuid)
        }
    }

    /**
     * Every frame of one copy to one device, in order: `<base>` alone, else `<base>-c<n>` (iOS
     * `DeviceDeliveryPlan.wireId`). Stops at the first frame not taken — a partial set never
     * reassembles, and the frames after it would advance the ratchet for nothing — and returns
     * that answer; otherwise the last one. A sealed frame gets its own id from the server, and a
     * decryption error names that id, so each is recorded against [localId].
     */
    private suspend fun sendFrames(
        frames: List<ByteArray>,
        wireIdBase: String,
        deviceId: String,
        myId: String,
        accountId: String,
        localId: String?,
        timestampMs: Long,
        identityPublic: ByteArray,
        isOwnReplica: Boolean,
    ): MessagingService.SendResult? {
        var last: MessagingService.SendResult? = null
        frames.forEachIndexed { index, frame ->
            if (index > 0) delay(Random.nextLong(CHUNK_JITTER_MIN_MS, CHUNK_JITTER_MAX_MS + 1))
            val wireMessageId = if (frames.size <= 1) wireIdBase else "$wireIdBase-c$index"
            val encrypted = encryptFor(deviceId, wireMessageId, frame) ?: return null
            val result = sendOneCopy(
                myId = myId,
                accountId = accountId,
                wireMessageId = wireMessageId,
                timestampMs = timestampMs,
                encrypted = encrypted,
                identityPublic = identityPublic,
                isOwnReplica = isOwnReplica,
            )
            if (result?.success != true) return result
            if (localId != null) serverMessageIds.record(result.messageId, localId)
            last = result
        }
        return last
    }

    private companion object {
        const val TAG = "SendMessageUseCase"
        const val MAX_ATTEMPTS = 3
        const val BACKOFF_MS = 400L

        /** iOS `ChunkedDeliveryConfig.chunkSendJitter*`: frames of one message are not a burst. */
        const val CHUNK_JITTER_MIN_MS = 50L
        const val CHUNK_JITTER_MAX_MS = 200L
    }
}
