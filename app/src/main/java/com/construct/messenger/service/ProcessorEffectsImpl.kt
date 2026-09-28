package com.construct.messenger.service

import android.util.Log
import com.construct.messenger.crypto.CryptoManager
import com.construct.messenger.data.local.AckStore
import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.data.local.SessionStateStore
import com.construct.messenger.data.local.db.ChatDao
import com.construct.messenger.data.local.db.ChatEntity
import com.construct.messenger.data.local.db.MessageDao
import com.construct.messenger.data.local.db.MessageEntity
import com.construct.messenger.data.local.db.UserDao
import com.construct.messenger.data.local.db.UserEntity
import com.construct.messenger.data.local.db.refreshChatPreview
import com.construct.messenger.data.model.DeliveryStatus
import com.construct.messenger.data.model.ReplyRef
import com.construct.messenger.domain.usecase.ReceivingOpenUseCase
import com.construct.messenger.domain.usecase.SendMessageUseCase
import com.construct.messenger.domain.usecase.SendContactCardUseCase
import com.construct.messenger.domain.usecase.SendReceiptUseCase
import com.construct.messenger.invite.AccountAddressBook
import com.construct.messenger.invite.AccountAddressSource
import com.construct.messenger.invite.ContactCardPayload
import com.construct.messenger.domain.usecase.SessionControlUseCase
import com.construct.messenger.util.ConversationId
import com.construct.messenger.util.DisplayNameGenerator
import com.construct.messenger.util.IncomingPlaintext
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
    private val receivingOpen: ReceivingOpenUseCase,
    // Lazy: CfeTimerBridge executes actions *through* these effects, so a direct dependency
    // would be a cycle. Only its executor is used, and only after an open has finished.
    private val actionExecutor: dagger.Lazy<CfeTimerBridge>,
    // Lazy for the same reason: a resend encrypts through the core, which answers through here.
    private val sendMessage: dagger.Lazy<SendMessageUseCase>,
    private val held: HeldEnvelopes,
    private val alerts: IncomingAlerts,
) : ProcessorEffects {

    override suspend fun onDecrypted(contactId: String, messageId: String, plaintext: ByteArray) {
        val accountId = sessionManager.accountIdForDevice(contactId) ?: contactId
        val decoded = IncomingPlaintext.decode(plaintext)
        if (decoded.knstContentType == ContentType.CONTENT_TYPE_DELIVERY_RECEIPT_VALUE) {
            IncomingReceipt.messageIds(plaintext).forEach { markDelivered(it) }
            ackStore.markProcessed(messageId, accountId)
            return
        }
        if (decoded.knstContentType == ContentType.CONTENT_TYPE_CONTACT_CARD_VALUE) {
            // Their card. The intake key in it has no reader on Android yet; the address does.
            IncomingPlaintext.knstPayload(plaintext)
                ?.let(ContactCardPayload::read)
                ?.accountAddress
                ?.let { addressBook.pin(accountId, it, AccountAddressSource.CARD) }
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
        if (!decoded.isUserVisible) {
            Log.d(TAG, "decrypted non-visible ${messageId.take(8)}… type=${decoded.knstContentType}")
            ackStore.markProcessed(messageId, accountId)
            return
        }
        // The receipt and the row name the sender's KNST id. The envelope id is what
        // the server redelivers, so the ACK stays on that.
        val rowId = storageId(decoded.e2eMessageId, messageId, sentByMe = false)
        persistIncoming(accountId, rowId, decoded.text, System.currentTimeMillis(), decoded.reply)
        ackStore.markProcessed(messageId, accountId)
        runCatching { sendReceiptUseCase.delivered(accountId, listOf(rowId)) }
            .onFailure { Log.w(TAG, "e2e receipt send failed", it) }
        // We heard from them: hand them our card if their device lacks it.
        runCatching { sendContactCard.sendIfOwed(accountId) }
            .onFailure { Log.w(TAG, "contact card send failed", it) }
        runCatching { sessionManager.fetchIdentityKey(contactId) }
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
        val routed = SenderSyncRouting.decode(plaintext)
        if (routed == null) {
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
        )
        ackStore.markProcessed(messageId, accountId)
    }

    override suspend fun onCallSignal(contactId: String, messageId: String, protoBytes: ByteArray) {
        val accountId = sessionManager.accountIdForDevice(contactId) ?: contactId
        Log.i(TAG, "call signal from ${accountId.take(8)}… ${messageId.take(8)}… (${protoBytes.size}B) — not wired")
        ackStore.markProcessed(messageId, accountId)
    }

    override suspend fun sendReceipt(messageId: String, toUserId: String, status: String) {
        // User-visible persist already sends an E2E receipt from onDecrypted.
        Log.d(TAG, "cfe receipt $status ${messageId.take(8)}…")
    }

    override suspend fun notifyNewMessage(chatId: String, preview: String) {
        // persistIncoming is the one place that knows whether a message is new and unseen,
        // so it raises the notification; this CFE hook only logs.
        Log.d(TAG, "notify $chatId preview=${preview.take(40)}")
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

    override suspend fun sendDecryptionError(contactId: String, messageId: String, payload: ByteArray) {
        Log.i(TAG, "could not read ${messageId.take(8)}… from ${contactId.take(8)}… — telling its writer")
        sessionControl.sendDecryptionError(contactId, payload)
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
        sendMessage.get().resend(account, contactId, messageId)
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
                    lastMessageText = text,
                    lastMessageTime = timestampMs,
                    unreadCount = if (unseen) 1 else 0,
                ),
            )
        } else {
            chatDao.updateLastMessage(chatId, text, timestampMs)
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
            ),
        )
        val existing = chatDao.getById(chatId)
        if (existing == null) {
            chatDao.upsert(
                ChatEntity(
                    id = chatId,
                    otherUserId = partnerUserId,
                    lastMessageText = text,
                    lastMessageTime = timestampMs,
                    unreadCount = 0,
                ),
            )
        } else {
            chatDao.updateLastMessage(chatId, text, timestampMs)
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
        messageDao.markEdited(row.id, text)
        refreshChatPreview(chatDao, messageDao, row.chatId)
    }

    private suspend fun applyDelete(targetMessageId: String, sentByMe: Boolean) {
        val row = messageDao.getByIdIgnoreCase(targetMessageId) ?: return
        if (row.isSentByMe != sentByMe) return
        messageDao.deleteById(row.id)
        refreshChatPreview(chatDao, messageDao, row.chatId)
    }

    private companion object {
        const val TAG = "ProcessorEffects"
    }
}
