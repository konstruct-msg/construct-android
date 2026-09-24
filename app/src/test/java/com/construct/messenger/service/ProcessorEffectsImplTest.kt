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
import com.construct.messenger.util.KnstFrame
import com.construct.messenger.domain.usecase.ResponderInitUseCase
import com.construct.messenger.util.SenderSyncRouting
import uniffi.construct_core.CfeAction
import uniffi.construct_core.CfeIncomingEvent
import uniffi.construct_core.CfeTearDownCause
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import shared.proto.messaging.v1.Content.MessageContent
import shared.proto.messaging.v1.Content.TextMessage
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.wheneverBlocking
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
            actionExecutor = { mock<CfeTimerBridge>() },
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

    @Test
    fun `onSenderSync strips SSR1 and persists sent copy under base id`() = runTest {
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
            actionExecutor = { mock<CfeTimerBridge>() },
        )
        val baseId = "550e8400-e29b-41d4-a716-446655440000"
        val content = MessageContent.newBuilder()
            .setText(TextMessage.newBuilder().setText("mirrored"))
            .build()
            .toByteArray()
        val knst = KnstFrame.pack(content, KnstFrame.TYPE_E2EE_SIGNAL, UUID.fromString(baseId))
        val routed = SenderSyncRouting.encode(peer, knst)

        effects.onSenderSync(
            contactId = "source-device-opaque",
            messageId = "$baseId-ss-0123456789abcdef",
            plaintext = routed,
            timestampMs = 42L,
        )

        val chatId = ConversationId.direct(myId, peer)
        assertEquals("mirrored", messages.rows[baseId]?.text)
        assertEquals(true, messages.rows[baseId]?.isSentByMe)
        assertEquals(42L, messages.rows[baseId]?.timestamp)
        assertEquals(chatId, messages.rows[baseId]?.chatId)
        assertEquals(0, chats.rows[chatId]?.unreadCount)
        assertTrue(acks.isProcessed("$baseId-ss-0123456789abcdef"))
    }

    // ── requestKeyBundle: what an init leaves behind ───────────────────────

    private val incoming = MessageRouter.IncomingMessage(
        messageId = "init-1",
        senderId = peer,
        contentType = shared.proto.core.v1.EnvelopeOuterClass.ContentType.CONTENT_TYPE_E2EE_SIGNAL,
        encryptedPayload = ByteArray(0),
        timestampMs = 0L,
        viaSealedSender = false,
    )

    private fun effectsFor(
        outcome: ResponderInitUseCase.Outcome,
        crypto: CryptoManager,
        bridge: CfeTimerBridge,
        acks: FakeAckStore,
    ): ProcessorEffectsImpl {
        val keystore: KeystoreManager = mock()
        whenever(keystore.getUserId()).thenReturn(myId)
        val responder: ResponderInitUseCase = mock()
        wheneverBlocking { responder.establish(any(), anyOrNull()) }.thenReturn(outcome)
        return ProcessorEffectsImpl(
            cryptoManager = crypto,
            keystoreManager = keystore,
            messageDao = FakeMessageDao(),
            chatDao = FakeChatDao(),
            userDao = FakeUserDao(),
            ackStore = acks,
            sessionStateStore = mock(),
            sessionManager = mock(),
            sessionControl = mock(),
            healSession = mock(),
            sendReceiptUseCase = mock(),
            responderInit = responder,
            actionExecutor = { bridge },
        )
    }

    @Test
    fun `established init executes what the core drained behind it`() = runTest {
        val drained = listOf<CfeAction>(CfeAction.NotifySessionCreated(contactId = "dev"))
        val bridge: CfeTimerBridge = mock()
        val outcome = ResponderInitUseCase.Outcome.Established(
            ResponderInitUseCase.Result("dev", "init-1", "hi".toByteArray()),
            drained,
        )

        effectsFor(outcome, mock(), bridge, FakeAckStore()).requestKeyBundle(peer, incoming)

        verifyBlocking(bridge) { execute(drained) }
    }

    @Test
    fun `failed init is let go and the core is asked to tear the sender down`() = runTest {
        val crypto: CryptoManager = mock()
        val answer = listOf<CfeAction>(CfeAction.SendEndSession(contactId = "dev"))
        whenever(crypto.handleEvent(any())).thenReturn(answer)
        val bridge: CfeTimerBridge = mock()
        val acks = FakeAckStore()

        effectsFor(ResponderInitUseCase.Outcome.Failed("dev"), crypto, bridge, acks)
            .requestKeyBundle(peer, incoming)

        assertTrue(acks.isProcessed("init-1"))
        verify(crypto).handleEvent(CfeIncomingEvent.TeardownRequested("dev", CfeTearDownCause.BLIND))
        verifyBlocking(bridge) { execute(answer) }
    }

    @Test
    fun `failed init without a named sender tears nothing down`() = runTest {
        val crypto: CryptoManager = mock()
        val acks = FakeAckStore()

        effectsFor(ResponderInitUseCase.Outcome.Failed(null), crypto, mock(), acks)
            .requestKeyBundle(peer, incoming)

        assertTrue(acks.isProcessed("init-1"))
        verify(crypto, never()).handleEvent(any())
    }

    @Test
    fun `init not attempted leaves the carrier for a later delivery`() = runTest {
        val acks = FakeAckStore()

        effectsFor(ResponderInitUseCase.Outcome.NotAttempted, mock(), mock(), acks)
            .requestKeyBundle(peer, incoming)

        assertFalse(acks.isProcessed("init-1"))
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
