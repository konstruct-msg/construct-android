package com.construct.messenger.domain.usecase

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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import uniffi.construct_core.CfeAction
import uniffi.construct_core.CfeIncomingEvent
import uniffi.construct_core.CfeSecureStoreSlot

class SendMessageUseCaseTest {

    private val myId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
    private val peer = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
    private val peerDevice = "11111111111111111111111111111111"

    @Test
    fun `identified send persists SENT after successful RPC`() = runTest {
        val messages = FakeMessageDao()
        val chats = FakeChatDao()
        val users = FakeUserDao()
        val sessions = mock<SessionStateStore>()
        val keystore: KeystoreManager = mock()
        whenever(keystore.getUserId()).thenReturn(myId)
        val crypto: CryptoManager = mock()
        whenever(crypto.isMessagingReady).thenReturn(true)
        val sessionManager: SessionManager = mock()
        whenever(sessionManager.ensureSession(peer)).thenReturn(
            SessionManager.SessionPeer(peer, peerDevice, byteArrayOf(1, 2, 3)),
        )
        val orchestrator: OrchestratorGateway = mock()
        whenever(orchestrator.handleEvent(any())).thenReturn(
            listOf(
                CfeAction.SaveToSecureStore(CfeSecureStoreSlot.Session(peerDevice), byteArrayOf(9)),
                CfeAction.SendEncryptedMessage(peerDevice, byteArrayOf(7, 7), "ignored", 0u),
            ),
        )
        whenever(sessions.saveCfeActions(any())).thenReturn(true)
        val messaging: MessagingService = mock()
        whenever(
            messaging.sendMessage(
                messageId = any(),
                senderId = eq(myId),
                recipientId = eq(peer),
                conversationId = eq(""),
                encryptedPayload = any(),
                timestampMs = any(),
                contentType = any(),
                sealedInner = anyOrNull(),
            ),
        ).thenReturn(
            MessagingService.SendResult("ok", true, "", true, 0, "a"),
        )
        val policy: StealthPolicy = mock()
        whenever(policy.shouldUseSealedSender()).thenReturn(false)

        val useCase = SendMessageUseCase(
            keystoreManager = keystore,
            sessionManager = sessionManager,
            orchestrator = orchestrator,
            cryptoManager = crypto,
            messagingService = messaging,
            stealthPolicy = policy,
            stealthSender = mock(),
            messageDao = messages,
            chatDao = chats,
            userDao = users,
            sessionStateStore = sessions,
        )

        val outcome = useCase(peer, "hello")

        assertTrue(outcome is SendOutcome.Sent)
        val id = (outcome as SendOutcome.Sent).messageId
        assertEquals(DeliveryStatus.SENT.name, messages.rows[id]?.deliveryStatus)
        assertEquals("hello", messages.rows[id]?.text)
        assertTrue(messages.rows[id]?.isSentByMe == true)
    }

    @Test
    fun `stealth on without identity key fails closed`() = runTest {
        val messages = FakeMessageDao()
        val keystore: KeystoreManager = mock()
        whenever(keystore.getUserId()).thenReturn(myId)
        val crypto: CryptoManager = mock()
        whenever(crypto.isMessagingReady).thenReturn(true)
        val sessionManager: SessionManager = mock()
        whenever(sessionManager.ensureSession(peer)).thenReturn(
            SessionManager.SessionPeer(peer, peerDevice, byteArrayOf()),
        )
        val orchestrator: OrchestratorGateway = mock()
        whenever(orchestrator.handleEvent(any())).thenReturn(
            listOf(
                CfeAction.SaveToSecureStore(CfeSecureStoreSlot.Session(peerDevice), byteArrayOf(9)),
                CfeAction.SendEncryptedMessage(peerDevice, byteArrayOf(7), "ignored", 0u),
            ),
        )
        val sessions = mock<SessionStateStore>()
        whenever(sessions.saveCfeActions(any())).thenReturn(true)
        val policy: StealthPolicy = mock()
        whenever(policy.shouldUseSealedSender()).thenReturn(true)

        val useCase = SendMessageUseCase(
            keystoreManager = keystore,
            sessionManager = sessionManager,
            orchestrator = orchestrator,
            cryptoManager = crypto,
            messagingService = mock(),
            stealthPolicy = policy,
            stealthSender = mock(),
            messageDao = messages,
            chatDao = FakeChatDao(),
            userDao = FakeUserDao(),
            sessionStateStore = sessions,
        )

        val outcome = useCase(peer, "secret")
        assertTrue(outcome is SendOutcome.Failed)
        val failed = outcome as SendOutcome.Failed
        assertTrue(failed.reason.contains("identity"))
        assertEquals(DeliveryStatus.FAILED.name, messages.rows[failed.messageId]?.deliveryStatus)
    }

    @Test
    fun `retryable send is retried then succeeds`() = runTest {
        val messages = FakeMessageDao()
        val keystore: KeystoreManager = mock()
        whenever(keystore.getUserId()).thenReturn(myId)
        val crypto: CryptoManager = mock()
        whenever(crypto.isMessagingReady).thenReturn(true)
        val sessionManager: SessionManager = mock()
        whenever(sessionManager.ensureSession(peer)).thenReturn(
            SessionManager.SessionPeer(peer, peerDevice, byteArrayOf(1)),
        )
        val orchestrator: OrchestratorGateway = mock()
        whenever(orchestrator.handleEvent(any())).thenReturn(
            listOf(
                CfeAction.SaveToSecureStore(CfeSecureStoreSlot.Session(peerDevice), byteArrayOf(9)),
                CfeAction.SendEncryptedMessage(peerDevice, byteArrayOf(7, 7), "ignored", 0u),
            ),
        )
        val messaging: MessagingService = mock()
        whenever(
            messaging.sendMessage(
                messageId = any(),
                senderId = eq(myId),
                recipientId = eq(peer),
                conversationId = eq(""),
                encryptedPayload = any(),
                timestampMs = any(),
                contentType = any(),
                sealedInner = anyOrNull(),
            ),
        ).thenReturn(
            MessagingService.SendResult("ok", false, "UNAVAILABLE", true, 0, "a"),
            MessagingService.SendResult("ok", true, "", true, 0, "b"),
        )
        val policy: StealthPolicy = mock()
        whenever(policy.shouldUseSealedSender()).thenReturn(false)
        val sessions = mock<SessionStateStore>()
        whenever(sessions.saveCfeActions(any())).thenReturn(true)

        val useCase = SendMessageUseCase(
            keystoreManager = keystore,
            sessionManager = sessionManager,
            orchestrator = orchestrator,
            cryptoManager = crypto,
            messagingService = messaging,
            stealthPolicy = policy,
            stealthSender = mock(),
            messageDao = messages,
            chatDao = FakeChatDao(),
            userDao = FakeUserDao(),
            sessionStateStore = sessions,
        )

        val outcome = useCase(peer, "hello")
        assertTrue(outcome is SendOutcome.Sent)
        org.mockito.kotlin.verify(messaging, org.mockito.kotlin.times(2)).sendMessage(
            messageId = any(),
            senderId = eq(myId),
            recipientId = eq(peer),
            conversationId = eq(""),
            encryptedPayload = any(),
            timestampMs = any(),
            contentType = any(),
            sealedInner = anyOrNull(),
        )
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
