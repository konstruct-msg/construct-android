package com.construct.messenger.service

import android.util.Log
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
import com.construct.messenger.util.ConversationId
import com.construct.messenger.util.DisplayNameGenerator
import com.construct.messenger.util.IncomingPlaintext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/**
 * Room / Keystore / session-store implementation of [ProcessorEffects].
 *
 * Healing, END_SESSION on the wire, and key-bundle fetch are logged and deferred
 * to the send/heal package — they must not drop the decrypted payload or the ACK.
 * Receipts currently persist locally; the unary send of a delivery receipt lands
 * with [com.construct.messenger.domain.usecase.SendMessageUseCase].
 */
@Singleton
class ProcessorEffectsImpl @Inject constructor(
    private val keystoreManager: KeystoreManager,
    private val messageDao: MessageDao,
    private val chatDao: ChatDao,
    private val userDao: UserDao,
    private val ackStore: AckStore,
    private val sessionStateStore: SessionStateStore,
    private val sessionManager: SessionManager,
) : ProcessorEffects {

    override suspend fun onDecrypted(contactId: String, messageId: String, plaintext: ByteArray) {
        val decoded = IncomingPlaintext.decode(plaintext)
        if (!decoded.isUserVisible) {
            Log.d(TAG, "decrypted non-visible ${messageId.take(8)}… type=${decoded.knstContentType}")
            ackStore.markProcessed(messageId, contactId)
            return
        }
        persistIncoming(contactId, messageId, decoded.text, System.currentTimeMillis())
        ackStore.markProcessed(messageId, contactId)
    }

    override suspend fun onCallSignal(contactId: String, messageId: String, protoBytes: ByteArray) {
        Log.i(TAG, "call signal from ${contactId.take(8)}… ${messageId.take(8)}… (${protoBytes.size}B) — not wired")
        ackStore.markProcessed(messageId, contactId)
    }

    override suspend fun persistMessage(messageJson: String) {
        // CFE's persist payload is a JSON snapshot; onDecrypted is the canonical
        // write. Duplicate REPLACE is harmless if both fire for the same id.
        Log.d(TAG, "persistMessage json=${messageJson.take(80)}")
    }

    override suspend fun sendReceipt(messageId: String, toUserId: String, status: String) {
        Log.d(TAG, "receipt $status for ${messageId.take(8)}… → ${toUserId.take(8)}… (local only until send path)")
        if (status == "delivered") {
            markDelivered(messageId)
        }
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

    override suspend fun saveSession(key: String, data: ByteArray) {
        sessionStateStore.saveSession(key, data)
        val contactId = contactIdFromStoreKey(key)
        if (sessionStateStore.getEstablishedAt(contactId) == null) {
            sessionStateStore.setEstablishedAt(contactId, System.currentTimeMillis())
        }
    }

    override suspend fun archiveSession(contactId: String) {
        sessionManager.removeSession(contactId)
        sessionStateStore.removeSession(sessionStoreKey(contactId))
        sessionStateStore.removeMeta(contactId)
    }

    override suspend fun requestHeal(contactId: String, role: String) {
        Log.w(TAG, "heal requested for ${contactId.take(8)}… role=$role — HealSessionUseCase not wired yet")
    }

    override suspend fun requestEndSession(contactId: String) {
        Log.w(TAG, "END_SESSION requested for ${contactId.take(8)}… — tearing down local session only")
        runCatching { archiveSession(contactId) }
            .onFailure { if (it is CancellationException) throw it else Log.e(TAG, "archive after END_SESSION failed", it) }
    }

    override suspend fun requestKeyBundle(userId: String, incoming: MessageRouter.IncomingMessage) {
        Log.w(TAG, "key bundle requested for ${userId.take(8)}… — responder init not wired yet")
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
        const val SESSION_KEY_PREFIX = "session:"

        fun sessionStoreKey(contactId: String) =
            if (contactId.startsWith(SESSION_KEY_PREFIX)) contactId else SESSION_KEY_PREFIX + contactId

        fun contactIdFromStoreKey(key: String) = key.removePrefix(SESSION_KEY_PREFIX)
    }
}
