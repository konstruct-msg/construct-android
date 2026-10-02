package com.construct.messenger.service

import com.construct.messenger.diagnostics.Log
import com.construct.messenger.crypto.CryptoManager
import com.construct.messenger.data.local.ReactionStore
import com.construct.messenger.data.local.AckStore
import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.data.local.SessionStateStore
import com.construct.messenger.data.local.db.ChatDao
import com.construct.messenger.data.local.db.ChatEntity
import com.construct.messenger.data.local.db.MessageDao
import com.construct.messenger.data.local.db.MessageEntity
import com.construct.messenger.data.local.db.applyEdit
import com.construct.messenger.data.local.db.UserDao
import com.construct.messenger.data.local.db.UserEntity
import com.construct.messenger.data.local.db.refreshChatPreview
import com.construct.messenger.data.model.DeliveryStatus
import com.construct.messenger.data.model.ReplyRef
import com.construct.messenger.domain.usecase.ReceivingOpenUseCase
import com.construct.messenger.domain.usecase.SendContactCardUseCase
import com.construct.messenger.domain.usecase.SendReceiptUseCase
import com.construct.messenger.invite.AccountAddressBook
import com.construct.messenger.stealth.IntakeCredentials
import com.construct.messenger.invite.AccountAddressSource
import com.construct.messenger.invite.ContactCardPayload
import com.construct.messenger.domain.usecase.SessionControlUseCase
import com.construct.messenger.util.ConversationId
import com.construct.messenger.util.DisplayNameGenerator
import com.construct.messenger.util.IncomingPlaintext
import com.construct.messenger.util.KnstFrame
import com.construct.messenger.util.MediaWire
import com.construct.messenger.util.ProfileShare
import com.construct.messenger.util.IncomingReceipt
import com.construct.messenger.util.SenderSyncRouting
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import shared.proto.core.v1.EnvelopeOuterClass.ContentType
import uniffi.construct_core.CfeSecureStoreSlot

/**
 * Room / Keystore / session-store implementation of [ProcessorEffects].
 *
 * Decryption errors / receipts / receiving opens / resends go to dedicated use cases.
 */
@Singleton
class ProcessorEffectsImpl @Inject constructor(
    private val cryptoManager: CryptoManager,
    private val keystoreManager: KeystoreManager,
    private val messageDao: MessageDao,
    private val chatDao: ChatDao,
    private val userDao: UserDao,
    private val ackStore: AckStore,
    private val sessionStateStore: SessionStateStore,
    private val sessionManager: SessionManager,
    private val sessionControl: SessionControlUseCase,
    private val sendReceiptUseCase: SendReceiptUseCase,
    private val sendContactCard: SendContactCardUseCase,
    private val addressBook: AccountAddressBook,
    private val intake: IntakeCredentials,
    private val receivingOpen: ReceivingOpenUseCase,
    // Lazy: CfeTimerBridge executes actions *through* these effects, so a direct dependency
    // would be a cycle. Only its executor is used, and only after an open has finished.
    private val actionExecutor: dagger.Lazy<CfeTimerBridge>,
    // Lazy for the same reason: a resend encrypts through the core, which answers through here.
    private val pendingResends: PendingResends,
    private val held: HeldEnvelopes,
    private val alerts: IncomingAlerts,
    private val chunks: ChunkReassembler,
    private val mediaPreview: MediaPreviewText,
    private val contactAvatars: ContactAvatars,
    private val reactions: ReactionStore,
    private val callSignals: com.construct.messenger.calls.CallSignalInbox,
) : ProcessorEffects {

    override suspend fun onDecrypted(contactId: String, messageId: String, plaintext: ByteArray) {
        val accountId = sessionManager.accountIdForDevice(contactId) ?: contactId
        val assembled = whole(accountId, messageId, plaintext) ?: return
        val decoded = IncomingPlaintext.decode(assembled)
        if (decoded.knstContentType == ContentType.CONTENT_TYPE_DELIVERY_RECEIPT_VALUE) {
            IncomingReceipt.messageIds(assembled).forEach { markDelivered(it) }
            ackStore.markProcessed(messageId, accountId)
            return
        }
        if (decoded.knstContentType == ContentType.CONTENT_TYPE_CALL_SIGNAL_VALUE) {
            // A sealed call signal: the envelope's type is GENERIC, so the core cannot tell it from
            // a message and hands it over decrypted; the real type is the frame's. iOS
            // `handleFramedSideChannel`. Without this every signal from iOS was dropped as a
            // non-visible message — the phone rang there, and the answer never landed here.
            val payload = IncomingPlaintext.knstPayload(assembled)
            if (payload == null) {
                Log.w(TAG, "call signal frame ${messageId.take(8)}… is truncated — dropped")
                ackStore.markProcessed(messageId, accountId)
            } else {
                onCallSignal(contactId, messageId, payload)
            }
            return
        }
        if (decoded.knstContentType == ContentType.CONTENT_TYPE_CONTACT_CARD_VALUE) {
            // Their card: the key our envelopes to them present instead of a token, and the address
            // they are named by.
            IncomingPlaintext.knstPayload(assembled)?.let(ContactCardPayload::read)?.let { card ->
                card.intakeKey?.let { intake.recordPeerKey(accountId, it) }
                card.accountAddress?.let { addressBook.pin(accountId, it, AccountAddressSource.CARD) }
            }
            ackStore.markProcessed(messageId, accountId)
            return
        }
        if (decoded.knstContentType == ContentType.CONTENT_TYPE_PROFILE_VALUE) {
            // Their profile, typed (iOS `handleFramedSideChannel` → `ProfileSharingManager.apply`).
            val profile = IncomingPlaintext.knstPayload(assembled)?.let(ProfileShare::read)
            if (profile == null) {
                Log.w(TAG, "profile ${messageId.take(8)}… does not parse — dropped")
            } else {
                applyProfile(accountId) { row, now -> ContactProfiles.typed(row, profile, now) }
            }
            ackStore.markProcessed(messageId, accountId)
            return
        }
        decoded.legacyProfile?.let { profile ->
            applyProfile(accountId) { row, now -> ContactProfiles.legacy(row, profile, now) }
            ackStore.markProcessed(messageId, accountId)
            return
        }
        decoded.edit?.let { edit ->
            applyEdit(edit, sentByMe = false)
            ackStore.markProcessed(messageId, accountId)
            return
        }
        decoded.delete?.let { deletion ->
            applyDelete(deletion.targetMessageId, sentByMe = false)
            ackStore.markProcessed(messageId, accountId)
            return
        }
        decoded.reaction?.let { reaction ->
            applyReaction(reaction, reactorUserId = accountId)
            ackStore.markProcessed(messageId, accountId)
            return
        }
        if (!decoded.isUserVisible) {
            Log.d(TAG, "decrypted non-visible ${messageId.take(8)}… type=${decoded.knstContentType}")
            ackStore.markProcessed(messageId, accountId)
            return
        }
        // The receipt and the row name the sender's KNST id. The envelope id is what
        // the server redelivers, so the ACK stays on that.
        val rowId = storageId(decoded.e2eMessageId, messageId, sentByMe = false)
        persistIncoming(accountId, rowId, decoded.text, System.currentTimeMillis(), decoded.reply, decoded.media)
        ackStore.markProcessed(messageId, accountId)
        runCatching { sendReceiptUseCase.delivered(accountId, listOf(rowId)) }
            .onFailure { Log.w(TAG, "e2e receipt send failed", it) }
        // We heard from them: hand them our card if their device lacks it.
        runCatching { sendContactCard.sendIfOwed(accountId) }
            .onFailure { Log.w(TAG, "contact card send failed", it) }
        runCatching { sessionManager.fetchIdentityKey(contactId) }
    }

    /**
     * [received] as one whole frame, or null when it was a chunk of a message not yet complete —
     * held, and its envelope acknowledged, because the bytes are in Room now — or a frame that
     * could never be put together.
     */
    private suspend fun whole(accountId: String, messageId: String, received: ByteArray): ByteArray? =
        when (val assembly = chunks.accept(accountId, received)) {
            is ChunkReassembler.Assembly.Ready -> assembly.plaintext
            ChunkReassembler.Assembly.Pending -> {
                ackStore.markProcessed(messageId, accountId)
                null
            }
            is ChunkReassembler.Assembly.Invalid -> {
                Log.w(TAG, "chunk ${messageId.take(8)}… dropped: ${assembly.reason}")
                ackStore.markProcessed(messageId, accountId)
                null
            }
        }

    /**
     * Our own copy, and whose conversation it belongs to. Two layouts arrive:
     * - iOS, and Android since 2026-09-30: a type-23 KNST stream whose joined payload is
     *   `SSR1 ‖ MessageContent` (`architecture/WIRE_FORMAT.md` — the header goes on before
     *   chunking, so a long copy carries it once);
     * - Android before that: `SSR1 ‖ KNST frame`, the header outside the frame. iOS never read it.
     *
     * Either way the answer is the partner and one ordinary type-1 frame. Null when a chunk was
     * held; [NOT_ROUTED] when no header names the partner.
     */
    private suspend fun routeSenderSync(accountId: String, messageId: String, received: ByteArray): SenderSyncRouting.Decoded? {
        SenderSyncRouting.decode(received)?.let { return it }
        val plaintext = whole(accountId, messageId, received) ?: return null
        val frame = KnstFrame.parse(plaintext) ?: return NOT_ROUTED
        val inner = IncomingPlaintext.knstPayload(plaintext) ?: return NOT_ROUTED
        val routed = SenderSyncRouting.decode(inner) ?: return NOT_ROUTED
        return routed.copy(payload = KnstFrame.whole(routed.payload, KnstFrame.TYPE_E2EE_SIGNAL, frame.messageId))
    }

    /**
     * They shared their profile ([ContactProfiles] decides what it changes). A contact we have no
     * row for is not created by it; the avatar it names is fetched afterwards ([ContactAvatars]).
     */
    private suspend fun applyProfile(accountId: String, decide: (UserEntity, Long) -> ContactProfiles.Applied?) {
        val row = userDao.getById(accountId) ?: run {
            Log.w(TAG, "profile from ${accountId.take(8)}… for a contact we do not hold — ignored")
            return
        }
        val applied = decide(row, System.currentTimeMillis()) ?: run {
            Log.i(TAG, "profile from ${accountId.take(8)}… not newer than the one held — ignored")
            return
        }
        userDao.upsert(applied.row)
        if (applied.fetchAvatar) contactAvatars.fetchPending(accountId)
        Log.i(TAG, "profile from ${accountId.take(8)}… applied")
    }

    override suspend fun onSenderSync(
        contactId: String,
        messageId: String,
        plaintext: ByteArray,
        timestampMs: Long,
    ) {
        val accountId = sessionManager.accountIdForDevice(contactId)
            ?: keystoreManager.getUserId()
            ?: contactId
        val routed = routeSenderSync(accountId, messageId, plaintext) ?: return
        if (routed === NOT_ROUTED) {
            Log.w(TAG, "sender-sync without SSR1 ${messageId.take(8)}… — acking")
            ackStore.markProcessed(messageId, accountId)
            return
        }
        val decoded = IncomingPlaintext.decode(routed.payload)
        decoded.edit?.let { edit ->
            applyEdit(edit, sentByMe = true)
            ackStore.markProcessed(messageId, accountId)
            return
        }
        decoded.delete?.let { deletion ->
            applyDelete(deletion.targetMessageId, sentByMe = true)
            ackStore.markProcessed(messageId, accountId)
            return
        }
        decoded.reaction?.let { reaction ->
            // Ours, from a sibling device: the reactor is this account.
            keystoreManager.getUserId()?.let { applyReaction(reaction, reactorUserId = it, fallbackMs = timestampMs) }
            ackStore.markProcessed(messageId, accountId)
            return
        }
        if (!decoded.isUserVisible) {
            Log.d(TAG, "sender-sync non-visible ${messageId.take(8)}… type=${decoded.knstContentType}")
            ackStore.markProcessed(messageId, accountId)
            return
        }
        val rowId = storageId(decoded.e2eMessageId, messageId, sentByMe = true)
        persistOutgoingCopy(
            partnerUserId = routed.partnerUserId,
            messageId = rowId,
            text = decoded.text,
            timestampMs = timestampMs,
            reply = decoded.reply,
            media = decoded.media,
        )
        ackStore.markProcessed(messageId, accountId)
    }

    /**
     * A call signal the core opened: the `WebRTCSignal` itself (the core took the frame off).
     * From a contact who is not blocked it goes to the call machine; from anyone else it is
     * dropped before anything reads it (iOS `handleCallSignalProto`). Acknowledged either way —
     * it is not a message, and redelivery would only ring again.
     */
    override suspend fun onCallSignal(contactId: String, messageId: String, protoBytes: ByteArray) {
        val accountId = sessionManager.accountIdForDevice(contactId) ?: contactId
        val signal = runCatching { shared.proto.signaling.v1.Webrtc.WebRTCSignal.parseFrom(protoBytes) }.getOrNull()
        val user = userDao.getById(accountId)
        when {
            signal == null || signal.callId.isEmpty() ->
                Log.w(TAG, "call signal from ${accountId.take(8)}… ${messageId.take(8)}… does not parse — dropped")
            !com.construct.messenger.calls.CallSignalInbox.admits(user?.isContact == true, user?.isBlocked == true) ->
                Log.i(TAG, "call signal from ${accountId.take(8)}… — not a callable contact, dropped")
            else -> {
                Log.i(TAG, "call signal ${signal.signalCase} call=${signal.callId.take(8)}… from ${accountId.take(8)}…")
                callSignals.deliver(com.construct.messenger.calls.CallSignalInbox.Incoming(accountId, contactId, signal))
            }
        }
        ackStore.markProcessed(messageId, accountId)
    }

    override suspend fun sendReceipt(messageId: String, toUserId: String, status: String) {
        // User-visible persist already sends an E2E receipt from onDecrypted.
        Log.d(TAG, "cfe receipt $status ${messageId.take(8)}…")
    }

    override suspend fun notifyNewMessage(chatId: String, preview: String) {
        // persistIncoming is the one place that knows whether a message is new and unseen,
        // so it raises the notification; this CFE hook only logs.
        Log.d(TAG, "notify ${chatId.take(8)}… (${preview.length} chars)")
    }

    override suspend fun markDelivered(messageId: String) {
        messageDao.updateDeliveryStatus(messageId, DeliveryStatus.DELIVERED.name)
    }

    override suspend fun markProcessed(messageId: String, senderId: String) {
        ackStore.markProcessed(messageId, senderId)
    }

    override suspend fun saveSecureStore(slot: CfeSecureStoreSlot, data: ByteArray) {
        sessionStateStore.saveSecureStore(slot, data)
    }

    override suspend fun pruneAckStore(cutoffTs: Long) {
        ackStore.prune(cutoffTs)
    }

    override suspend fun archiveSession(contactId: String) {
        sessionManager.removeSession(contactId)
        sessionStateStore.saveSecureStore(CfeSecureStoreSlot.Session(contactId), ByteArray(0))
    }

    override suspend fun sendDecryptionError(contactId: String, messageId: String, payload: ByteArray, enveloped: Boolean) {
        Log.i(TAG, "could not read ${messageId.take(8)}… from ${contactId.take(8)}… — telling its writer")
        sessionControl.sendDecryptionError(contactId, payload, enveloped)
    }

    override suspend fun sessionRetired(contactId: String, withoutOneTimePrekey: Boolean) {
        Log.i(TAG, "state with ${contactId.take(8)}… retired — the next send opens a new one" +
            if (withoutOneTimePrekey) ", without a one-time prekey" else "")
        if (withoutOneTimePrekey) sessionManager.openNextWithoutOneTimePrekey(contactId)
    }

    override suspend fun resendMessage(contactId: String, messageId: String) {
        val account = sessionManager.accountIdForDevice(contactId) ?: run {
            Log.i(TAG, "resend ${messageId.take(8)}… — device ${contactId.take(8)}… of no known contact")
            return
        }
        pendingResends.enqueue(account, contactId, messageId)
    }

    override suspend fun openReceiving(
        device: String,
        trigger: MessageRouter.IncomingMessage?,
    ): ProcessingOutcome {
        return when (val outcome = receivingOpen.open(device, trigger?.senderCertificate)) {
            is ReceivingOpenUseCase.Outcome.Opened -> {
                // The save, the opener's decrypt, what drained behind it, an archived session.
                // Unexecuted, the messages that waited for this session are lost (seen 2026-09-24).
                runCatching { actionExecutor.get().execute(outcome.actions) }
                    .onFailure { Log.e(TAG, "actions after open ${device.take(8)}… failed", it) }
                val handled = trigger == null || ackStore.isProcessed(trigger.messageId)
                if (handled) ProcessingOutcome.Processed else ProcessingOutcome.Deferred
            }
            is ReceivingOpenUseCase.Outcome.Failed -> {
                runCatching { actionExecutor.get().execute(outcome.actions) }
                    .onFailure { Log.e(TAG, "actions after failed open ${device.take(8)}… failed", it) }
                // Lost, as on iOS: acknowledged so a redelivery does not fail first in line again
                // and keep everything behind it queued.
                val given = (outcome.tried + outcome.dropped + listOfNotNull(trigger?.messageId)).distinct()
                given.forEach { held.take(it) }
                release(given)
                // The writer of each message given up is told by the core, with a decryption
                // error among the actions above — and only a writer the server vouched for.
                ProcessingOutcome.Acked
            }
            ReceivingOpenUseCase.Outcome.Unreachable -> ProcessingOutcome.Deferred
        }
    }

    override suspend fun release(messageIds: List<String>) {
        messageIds.forEach { ackStore.markProcessed(it, "") }
    }

    override fun isAckedInDb(messageId: String): Boolean = ackStore.isProcessed(messageId)

    private suspend fun persistIncoming(
        contactId: String,
        messageId: String,
        text: String,
        timestampMs: Long,
        reply: ReplyRef?,
        media: MediaWire.Stored? = null,
    ) {
        val myId = keystoreManager.getUserId() ?: run {
            Log.e(TAG, "persistIncoming: no local user id — dropping ${messageId.take(8)}…")
            return
        }
        val chatId = ConversationId.direct(myId, contactId)
        val prior = messageDao.getByIdIgnoreCase(messageId)
        // A later edit already replaced this text. Putting the original back is how a
        // redelivery undoes the peer's correction. A row we sent is not this incoming one.
        if (prior != null && (prior.isSentByMe || prior.isEdited)) return
        // A redelivered message (the ACK was lost, or the queue was replayed) is already here:
        // counting it again would inflate unread, and alerting again would ring for nothing.
        val firstSight = prior == null
        messageDao.insert(
            MessageEntity(
                id = messageId,
                chatId = chatId,
                text = text,
                isSentByMe = false,
                timestamp = timestampMs,
                deliveryStatus = DeliveryStatus.DELIVERED.name,
                replyToId = reply?.messageId,
                replyPreview = reply?.preview?.ifEmpty { null },
                replyMediaType = reply?.mediaType,
                mediaType = media?.kind,
                mediaPayload = media?.bytes,
            ),
        )
        // On screen, it is read as it lands.
        val unseen = firstSight && !alerts.isChatVisible(contactId)
        val existing = chatDao.getById(chatId)
        if (existing == null) {
            chatDao.upsert(
                ChatEntity(
                    id = chatId,
                    otherUserId = contactId,
                    lastMessageText = media?.let(mediaPreview::of) ?: text,
                    lastMessageTime = timestampMs,
                    unreadCount = if (unseen) 1 else 0,
                ),
            )
        } else {
            chatDao.updateLastMessage(chatId, media?.let(mediaPreview::of) ?: text, timestampMs)
            if (unseen) chatDao.incrementUnreadCount(chatId)
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
        if (unseen) {
            runCatching { alerts.onUnseenMessage(contactId) }
                .onFailure { Log.w(TAG, "message notification failed", it) }
        }
    }

    private suspend fun persistOutgoingCopy(
        partnerUserId: String,
        messageId: String,
        text: String,
        timestampMs: Long,
        reply: ReplyRef?,
        media: MediaWire.Stored? = null,
    ) {
        val myId = keystoreManager.getUserId() ?: run {
            Log.e(TAG, "persistOutgoingCopy: no local user id — dropping ${messageId.take(8)}…")
            return
        }
        val chatId = ConversationId.direct(myId, partnerUserId)
        val prior = messageDao.getByIdIgnoreCase(messageId)
        if (prior != null && (!prior.isSentByMe || prior.isEdited)) return
        messageDao.insert(
            MessageEntity(
                id = messageId,
                chatId = chatId,
                text = text,
                isSentByMe = true,
                timestamp = timestampMs,
                deliveryStatus = DeliveryStatus.SENT.name,
                replyToId = reply?.messageId,
                replyPreview = reply?.preview?.ifEmpty { null },
                replyMediaType = reply?.mediaType,
                mediaType = media?.kind,
                mediaPayload = media?.bytes,
            ),
        )
        val existing = chatDao.getById(chatId)
        if (existing == null) {
            chatDao.upsert(
                ChatEntity(
                    id = chatId,
                    otherUserId = partnerUserId,
                    lastMessageText = media?.let(mediaPreview::of) ?: text,
                    lastMessageTime = timestampMs,
                    unreadCount = 0,
                ),
            )
        } else {
            chatDao.updateLastMessage(chatId, media?.let(mediaPreview::of) ?: text, timestampMs)
        }
        if (userDao.getById(partnerUserId) == null) {
            userDao.upsert(
                UserEntity(
                    id = partnerUserId,
                    displayName = DisplayNameGenerator.generate(partnerUserId),
                    isContact = true,
                ),
            )
        }
    }

    /**
     * Prefer the sender's KNST id. Fall back to the envelope id when that id already
     * belongs to the other author — the same collision guard iOS uses — so a rewrite
     * of the envelope id does not fork the row edits and quotes look up.
     */
    private suspend fun storageId(e2eMessageId: String?, envelopeMessageId: String, sentByMe: Boolean): String {
        val preferred = (
            e2eMessageId
                ?: DeviceCopyRoute.parse(envelopeMessageId)?.baseMessageId
                ?: envelopeMessageId
            ).lowercase()
        val existing = messageDao.getByIdIgnoreCase(preferred)
        if (existing == null || existing.isSentByMe == sentByMe) return existing?.id ?: preferred
        return envelopeMessageId.lowercase()
    }

    /** A peer may only edit what they sent. A sender-sync copy may only edit what we sent. */
    private suspend fun applyEdit(edit: IncomingPlaintext.Edit, sentByMe: Boolean) {
        val row = messageDao.getByIdIgnoreCase(edit.targetMessageId) ?: run {
            Log.w(TAG, "edit target missing ${edit.targetMessageId.take(8)}…")
            return
        }
        if (row.isSentByMe != sentByMe) {
            Log.w(TAG, "edit rejected ${edit.targetMessageId.take(8)}… author mismatch")
            return
        }
        val text = edit.newText.ifEmpty { row.text }
        // iOS: `new_text` carries a photo's caption too, and the album keeps it.
        messageDao.applyEdit(row, text)
        refreshChatPreview(chatDao, messageDao, row.chatId)
    }

    private suspend fun applyDelete(targetMessageId: String, sentByMe: Boolean) {
        val row = messageDao.getByIdIgnoreCase(targetMessageId) ?: return
        if (row.isSentByMe != sentByMe) return
        messageDao.deleteById(row.id)
        refreshChatPreview(chatDao, messageDao, row.chatId)
    }

    /**
     * Metadata on the target, never a row. **Canon:** iOS `handleIncomingReaction`. Applied whether
     * or not the message is here yet — an orphan waits for it. A malformed one is still acknowledged
     * by the caller, so it cannot come back.
     */
    private suspend fun applyReaction(
        reaction: IncomingPlaintext.Reaction,
        reactorUserId: String,
        fallbackMs: Long = System.currentTimeMillis(),
    ) {
        val decision = reactions.applyIncoming(
            targetMessageId = reaction.targetMessageId,
            reactorUserId = reactorUserId,
            actionRawValue = reaction.actionRawValue,
            emoji = reaction.emoji,
            payloadTimestampMs = reaction.timestampMs,
            fallbackTimestampMs = fallbackMs,
            nowMs = System.currentTimeMillis(),
        )
        Log.i(TAG, "reaction on ${reaction.targetMessageId.take(8)}… from ${reactorUserId.take(8)}… $decision")
    }

    private companion object {
        const val TAG = "ProcessorEffects"

        /** A sender-sync copy that names no partner: nothing says whose conversation it is. */
        val NOT_ROUTED = SenderSyncRouting.Decoded(partnerUserId = "", payload = ByteArray(0))
    }
}
