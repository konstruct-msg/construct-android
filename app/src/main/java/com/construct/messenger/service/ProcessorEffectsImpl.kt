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
import com.construct.messenger.data.model.DeliveryStatus
import com.construct.messenger.domain.usecase.HealSessionUseCase
import com.construct.messenger.domain.usecase.ResponderInitUseCase
import com.construct.messenger.domain.usecase.SendReceiptUseCase
import com.construct.messenger.domain.usecase.SessionControlUseCase
import com.construct.messenger.util.ConversationId
import com.construct.messenger.util.DisplayNameGenerator
import com.construct.messenger.util.IncomingPlaintext
import com.construct.messenger.util.IncomingReceipt
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import shared.proto.core.v1.EnvelopeOuterClass.ContentType
import uniffi.construct_core.CfeSecureStoreSlot

/**
 * Room / Keystore / session-store implementation of [ProcessorEffects].
 *
 * Heal / END_SESSION / receipts / responder-init go to dedicated use cases.
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
    private val healSession: HealSessionUseCase,
    private val sendReceiptUseCase: SendReceiptUseCase,
    private val responderInit: ResponderInitUseCase,
) : ProcessorEffects {

    override suspend fun onDecrypted(contactId: String, messageId: String, plaintext: ByteArray) {
        val accountId = sessionManager.accountIdForDevice(contactId) ?: contactId
        val decoded = IncomingPlaintext.decode(plaintext)
        if (decoded.knstContentType == ContentType.CONTENT_TYPE_DELIVERY_RECEIPT_VALUE) {
            IncomingReceipt.messageIds(plaintext).forEach { markDelivered(it) }
            ackStore.markProcessed(messageId, accountId)
            return
        }
        if (!decoded.isUserVisible) {
            Log.d(TAG, "decrypted non-visible ${messageId.take(8)}… type=${decoded.knstContentType}")
            ackStore.markProcessed(messageId, accountId)
            return
        }
        persistIncoming(accountId, messageId, decoded.text, System.currentTimeMillis())
        ackStore.markProcessed(messageId, accountId)
        runCatching { sendReceiptUseCase.delivered(accountId, listOf(messageId)) }
            .onFailure { Log.w(TAG, "e2e receipt send failed", it) }
        runCatching { sessionManager.fetchIdentityKey(contactId) }
    }

    override suspend fun onCallSignal(contactId: String, messageId: String, protoBytes: ByteArray) {
        val accountId = sessionManager.accountIdForDevice(contactId) ?: contactId
        Log.i(TAG, "call signal from ${accountId.take(8)}… ${messageId.take(8)}… (${protoBytes.size}B) — not wired")
        ackStore.markProcessed(messageId, accountId)
    }

    override suspend fun persistMessage(messageJson: String) {
        // CFE's persist payload is a JSON snapshot; onDecrypted is the canonical
        // write. Duplicate REPLACE is harmless if both fire for the same id.
        Log.d(TAG, "persistMessage json=${messageJson.take(80)}")
    }

    override suspend fun sendReceipt(messageId: String, toUserId: String, status: String) {
        // User-visible persist already sends an E2E receipt from onDecrypted.
        Log.d(TAG, "cfe receipt $status ${messageId.take(8)}…")
    }

    override suspend fun notifyNewMessage(chatId: String, preview: String) {
        // No FCM yet. Unread is incremented in persistIncoming.
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

    override suspend fun applyPqContribution(contactId: String, kemSharedSecret: ByteArray) {
        cryptoManager.applyPqContribution(contactId, kemSharedSecret)
    }

    override suspend fun sessionTerminated(contactId: String, archiveBytes: ByteArray) {
        if (archiveBytes.isNotEmpty()) {
            sessionStateStore.saveSecureStore(
                CfeSecureStoreSlot.SessionArchive(contactId),
                archiveBytes,
            )
        }
        sessionManager.removeSession(contactId)
        sessionStateStore.saveSecureStore(CfeSecureStoreSlot.Session(contactId), ByteArray(0))
        sessionStateStore.removeMeta(contactId)
        Log.i(TAG, "session terminated ${contactId.take(8)}…; archive=${archiveBytes.size}B")
    }

    override suspend fun pruneAckStore(cutoffTs: Long) {
        ackStore.prune(cutoffTs)
    }

    override suspend fun sendHeartbeat(contactId: String) {
        sessionControl.sendPing(contactId)
    }

    override suspend fun archiveSession(contactId: String) {
        sessionManager.removeSession(contactId)
        sessionStateStore.saveSecureStore(CfeSecureStoreSlot.Session(contactId), ByteArray(0))
        sessionStateStore.removeMeta(contactId)
    }

    override suspend fun requestHeal(contactId: String, role: String) {
        healSession.heal(contactId, role)
    }

    override suspend fun requestEndSession(contactId: String) {
        sessionControl.sendEndSession(contactId)
    }

    override suspend fun requestKeyBundle(userId: String, incoming: MessageRouter.IncomingMessage) {
        val result = responderInit.establish(incoming) ?: return
        onDecrypted(result.contactId, result.messageId, result.plaintext)
    }

    override fun isAckedInDb(messageId: String): Boolean = ackStore.isProcessed(messageId)

    private suspend fun persistIncoming(contactId: String, messageId: String, text: String, timestampMs: Long) {
        val myId = keystoreManager.getUserId() ?: run {
            Log.e(TAG, "persistIncoming: no local user id — dropping ${messageId.take(8)}…")
            return
        }
        val chatId = ConversationId.direct(myId, contactId)
        messageDao.insert(
            MessageEntity(
                id = messageId,
                chatId = chatId,
                text = text,
                isSentByMe = false,
                timestamp = timestampMs,
                deliveryStatus = DeliveryStatus.DELIVERED.name,
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
                    unreadCount = 1,
                ),
            )
        } else {
            chatDao.updateLastMessage(chatId, text, timestampMs)
            chatDao.incrementUnreadCount(chatId)
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

    private companion object {
        const val TAG = "ProcessorEffects"
    }
}
