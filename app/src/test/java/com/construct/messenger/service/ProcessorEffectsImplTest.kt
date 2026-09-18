package com.construct.messenger.service

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
import com.construct.messenger.util.ConversationId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class ProcessorEffectsImplTest {

    private val myId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
    private val peer = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"

    @Test
    fun `onDecrypted persists message chat and contact`() = runTest {
        val messages = FakeMessageDao()
        val chats = FakeChatDao()
        val users = FakeUserDao()
        val acks = FakeAckStore()
        val keystore: KeystoreManager = mock()
        whenever(keystore.getUserId()).thenReturn(myId)

        val effects = ProcessorEffectsImpl(
            cryptoManager = mock<CryptoManager>(),
            keystoreManager = keystore,
            messageDao = messages,
            chatDao = chats,
            userDao = users,
            ackStore = acks,
            sessionStateStore = mock(),
            sessionManager = mock(),
            sessionControl = mock(),
            healSession = mock(),
            sendReceiptUseCase = mock(),
            responderInit = mock(),
        )

        effects.onDecrypted(peer, "msg-1", "hello".toByteArray())

        val chatId = ConversationId.direct(myId, peer)
        assertEquals("hello", messages.rows["msg-1"]?.text)
        assertEquals(chatId, messages.rows["msg-1"]?.chatId)
        assertEquals(peer, chats.rows[chatId]?.otherUserId)
        assertEquals(1, chats.rows[chatId]?.unreadCount)
        assertEquals(peer, users.rows[peer]?.id)
        assertTrue(acks.isProcessed("msg-1"))
    }
}

private class FakeMessageDao : MessageDao {
    val rows = linkedMapOf<String, MessageEntity>()
    override fun observeChat(chatId: String) = MutableStateFlow(rows.values.filter { it.chatId == chatId })
    override suspend fun getById(messageId: String) = rows[messageId]
    override suspend fun insert(message: MessageEntity) { rows[message.id] = message }
    override suspend fun updateDeliveryStatus(messageId: String, status: String) {
        rows[messageId]?.let { rows[messageId] = it.copy(deliveryStatus = status) }
    }
    override suspend fun deleteChat(chatId: String) { rows.values.removeAll { it.chatId == chatId } }
}

private class FakeChatDao : ChatDao {
    val rows = linkedMapOf<String, ChatEntity>()
    override fun observeAll() = MutableStateFlow(rows.values.toList())
    override suspend fun getById(chatId: String) = rows[chatId]
    override suspend fun getAllIds() = rows.keys.toList()
    override suspend fun upsert(chat: ChatEntity) { rows[chat.id] = chat }
    override suspend fun updateLastMessage(chatId: String, text: String?, timeMs: Long) {
        rows[chatId]?.let { rows[chatId] = it.copy(lastMessageText = text, lastMessageTime = timeMs) }
    }
    override suspend fun updateUnreadCount(chatId: String, count: Int) {
        rows[chatId]?.let { rows[chatId] = it.copy(unreadCount = count) }
    }
    override suspend fun incrementUnreadCount(chatId: String) {
        rows[chatId]?.let { rows[chatId] = it.copy(unreadCount = it.unreadCount + 1) }
    }
    override suspend fun delete(chatId: String) { rows.remove(chatId) }
}

private class FakeUserDao : UserDao {
    val rows = linkedMapOf<String, UserEntity>()
    override fun observeContacts(): Flow<List<UserEntity>> = MutableStateFlow(rows.values.filter { it.isContact })
    override fun observeAll(): Flow<List<UserEntity>> = MutableStateFlow(rows.values.toList())
    override suspend fun getById(userId: String) = rows[userId]
    override suspend fun upsert(user: UserEntity) { rows[user.id] = user }
    override suspend fun delete(userId: String) { rows.remove(userId) }
}

private class FakeAckStore : AckStore {
    private val ids = mutableSetOf<String>()
    override suspend fun hydrate() = Unit
    override fun isProcessed(messageId: String) = messageId in ids
    override suspend fun markProcessed(messageId: String, senderId: String) { ids += messageId }
    override suspend fun prune(olderThanMs: Long) = 0
}
