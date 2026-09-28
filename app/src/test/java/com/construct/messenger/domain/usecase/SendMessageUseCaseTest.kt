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
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import uniffi.construct_core.BinaryKeyBundle
import uniffi.construct_core.CfeAction
import uniffi.construct_core.CfeSecureStoreSlot
import uniffi.construct_core.DeliveryAudience
import uniffi.construct_core.DeliveryTarget

/**
 * The send path after §B item 1 of `decisions/a-peer-is-a-set-of-devices`.
 *
 * There is no primary send. Every device of the recipient gets its own copy, named
 * `<base>-fd-<tag>`, sealed to that device's identity key and retried the same way — where one
 * device used to get an ordinary account-addressed send with retries and a status, and the rest
 * got a fan-out with none of that.
 */
class SendMessageUseCaseTest {

    private val myId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
    private val peer = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
    private val pinnedDevice = "11111111111111111111111111111111"
    private val siblingDevice = "22222222222222222222222222222222"
    private val ourDevice = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

    /**
     * One peer with two devices, the transport accepting everything. Each test narrows what it
     * cares about; everything else answers the ordinary way, because a stub that differs from the
     * ordinary case is the thing a reader has to notice.
     */
    private class Harness {
        val messages = FakeMessageDao()
        val chats = FakeChatDao()
        val users = FakeUserDao()
        val sessions: SessionStateStore = mock()
        val keystore: KeystoreManager = mock()
        val crypto: CryptoManager = mock()
        val sessionManager: SessionManager = mock()
        val orchestrator: OrchestratorGateway = mock()
        val messaging: MessagingService = mock()
        val policy: StealthPolicy = mock()
        val stealthSender: StealthSenderService = mock()

        fun useCase() = SendMessageUseCase(
            keystoreManager = keystore,
            sessionManager = sessionManager,
            orchestrator = orchestrator,
            cryptoManager = crypto,
            messagingService = messaging,
            stealthPolicy = policy,
            stealthSender = stealthSender,
            messageDao = messages,
            chatDao = chats,
            userDao = users,
            sessionStateStore = sessions,
        )
    }

    private suspend fun harness(
        devices: List<String> = listOf("11111111111111111111111111111111", "22222222222222222222222222222222"),
        accepted: Boolean = true,
    ): Harness {
        val h = Harness()
        whenever(h.keystore.getUserId()).thenReturn(myId)
        whenever(h.crypto.isMessagingReady).thenReturn(true)
        whenever(h.crypto.currentDeviceId()).thenReturn(ourDevice)
        whenever(h.sessions.saveCfeActions(any())).thenReturn(true)
        whenever(h.policy.shouldUseSealedSender()).thenReturn(false)

        whenever(h.sessionManager.ensureSession(peer)).thenReturn(
            SessionManager.SessionPeer(peer, pinnedDevice, byteArrayOf(1, 2, 3)),
        )
        whenever(h.sessionManager.knownPeerDevices(peer)).thenReturn(
            devices.map { SessionManager.SessionPeer(peer, it, byteArrayOf(1, 2, 3)) },
        )
        whenever(h.sessionManager.discoverPeerBundles(peer)).thenReturn(
            devices.map {
                SessionManager.PeerBundle(
                    accountId = peer,
                    deviceId = it,
                    identityPublic = byteArrayOf(4, 5, 6),
                    bundle = mock<BinaryKeyBundle>(),
                )
            },
        )
        whenever(h.sessionManager.discoverOwnDeviceBundles(myId)).thenReturn(emptyList())
        devices.forEach { device ->
            whenever(h.sessionManager.ensureSessionForDevice(peer, device))
                .thenReturn(SessionManager.SessionPeer(peer, device, byteArrayOf(4, 5, 6)))
            whenever(h.crypto.deviceCopyTag(any(), eq(device), any())).thenReturn(device.take(16))
        }
        whenever(h.crypto.planSend(any(), any(), any(), any())).thenReturn(
            devices.map { DeliveryTarget(it, DeliveryAudience.RECIPIENT) },
        )
        whenever(h.orchestrator.handleEvent(any())).thenAnswer { invocation ->
            val event = invocation.arguments[0] as uniffi.construct_core.CfeIncomingEvent.OutgoingMessage
            listOf(
                CfeAction.SaveToSecureStore(CfeSecureStoreSlot.Session(event.contactId), byteArrayOf(9)),
                CfeAction.SendEncryptedMessage(event.contactId, byteArrayOf(7, 7), "ignored", 0u),
            )
        }
        whenever(
            h.messaging.sendMessage(
                messageId = any(),
                senderId = any(),
                recipientId = any(),
                conversationId = any(),
                encryptedPayload = any(),
                timestampMs = any(),
                contentType = any(),
            ),
        ).thenReturn(
            MessagingService.SendResult("ok", accepted, if (accepted) "" else "PERMISSION_DENIED", false, 0, "a"),
        )
        return h
    }

    private suspend fun sentMessageIds(h: Harness): List<String> {
        val captor = argumentCaptor<String>()
        verify(h.messaging, org.mockito.kotlin.atLeastOnce()).sendMessage(
            messageId = captor.capture(),
            senderId = any(),
            recipientId = any(),
            conversationId = any(),
            encryptedPayload = any(),
            timestampMs = any(),
            contentType = any(),
        )
        return captor.allValues
    }

    @Test
    fun `a successful send persists SENT`() = runTest {
        val h = harness()
        val outcome = h.useCase()(peer, "hello")

        assertTrue(outcome is SendOutcome.Sent)
        val id = (outcome as SendOutcome.Sent).messageId
        assertEquals(DeliveryStatus.SENT.name, h.messages.rows[id]?.deliveryStatus)
        assertEquals("hello", h.messages.rows[id]?.text)
        assertTrue(h.messages.rows[id]?.isSentByMe == true)
    }

    /**
     * The claim this change exists for. Two devices, two copies, and **neither** is the bare
     * message id — the copy that used to be the privileged one is named like every other.
     *
     * Mutation: give one device the bare `messageId` again — this reddens.
     */
    @Test
    fun `every device of the peer gets its own device-named copy`() = runTest {
        val h = harness()
        val outcome = h.useCase()(peer, "hello")
        assertTrue(outcome is SendOutcome.Sent)

        val ids = sentMessageIds(h)
        assertEquals(2, ids.size)
        assertTrue("no copy may carry the bare id", ids.none { !it.contains("-fd-") })
        assertTrue(ids.any { it.endsWith("-fd-" + pinnedDevice.take(16)) })
        assertTrue(ids.any { it.endsWith("-fd-" + siblingDevice.take(16)) })
    }

    /**
     * The whole set goes to the core, with nothing subtracted on the way. `primary_send_covered`
     * was the parameter that used to subtract one device here, and it is gone from `plan_send`
     * entirely now that no client sends a privileged copy.
     *
     * Mutation: drop the pinned device from the list handed to the core — this reddens.
     */
    @Test
    fun `the whole device set is handed to the core`() = runTest {
        val h = harness()
        h.useCase()(peer, "hello")

        val recipients = argumentCaptor<List<String>>()
        verify(h.crypto).planSend(recipients.capture(), any(), any(), any())
        assertEquals(listOf(pinnedDevice, siblingDevice), recipients.firstValue)
    }

    /**
     * The device set is the local one, corrected by the directory — a send to a peer we already
     * hold sessions with must not wait on the key server.
     */
    @Test
    fun `a device the directory has not answered for is still sent to`() = runTest {
        val h = harness()
        whenever(h.sessionManager.discoverPeerBundles(peer)).thenThrow(RuntimeException("offline"))
        val recipients = argumentCaptor<List<String>>()

        val outcome = h.useCase()(peer, "hello")

        assertTrue(outcome is SendOutcome.Sent)
        verify(h.crypto).planSend(recipients.capture(), any(), any(), any())
        assertEquals(listOf(pinnedDevice, siblingDevice), recipients.firstValue)
    }

    /**
     * One accepted copy is what `sent` has always meant — the message is in the person's mailbox.
     * A device that refused is a log line, not a failed send.
     */
    @Test
    fun `one accepted copy is enough for SENT`() = runTest {
        val h = harness()
        whenever(h.sessionManager.ensureSessionForDevice(peer, siblingDevice))
            .thenThrow(RuntimeException("no bundle"))

        val outcome = h.useCase()(peer, "hello")

        assertTrue(outcome is SendOutcome.Sent)
        assertEquals(1, sentMessageIds(h).size)
    }

    /**
     * And none accepted is a failure, rather than the `sent` a fan-out used to report by saying
     * nothing at all.
     *
     * Mutation: fold on `>= 0` — this reddens.
     */
    @Test
    fun `no accepted copy is a failed send`() = runTest {
        val h = harness(accepted = false)

        val outcome = h.useCase()(peer, "hello")

        assertTrue(outcome is SendOutcome.Failed)
        val failed = outcome as SendOutcome.Failed
        assertEquals(DeliveryStatus.FAILED.name, h.messages.rows[failed.messageId]?.deliveryStatus)
    }

    /**
     * Fail closed: with stealth on, an empty identity key is an identified downgrade wearing a
     * seal's name. The copy is refused rather than sealed to nothing — and with no copy left,
     * the send fails.
     */
    @Test
    fun `stealth on without an identity key fails closed`() = runTest {
        val h = harness(devices = listOf(pinnedDevice))
        whenever(h.policy.shouldUseSealedSender()).thenReturn(true)
        whenever(h.sessionManager.knownPeerDevices(peer)).thenReturn(
            listOf(SessionManager.SessionPeer(peer, pinnedDevice, byteArrayOf())),
        )
        whenever(h.sessionManager.discoverPeerBundles(peer)).thenReturn(emptyList())
        whenever(h.sessionManager.ensureSessionForDevice(peer, pinnedDevice))
            .thenReturn(SessionManager.SessionPeer(peer, pinnedDevice, byteArrayOf()))

        val outcome = h.useCase()(peer, "secret")

        assertTrue(outcome is SendOutcome.Failed)
        val failed = outcome as SendOutcome.Failed
        assertTrue(failed.reason.contains("identity"))
        assertEquals(DeliveryStatus.FAILED.name, h.messages.rows[failed.messageId]?.deliveryStatus)
    }

    /**
     * The retry budget is per copy now. It used to belong to the primary send alone: a sibling
     * device that answered UNAVAILABLE was simply logged.
     *
     * Mutation: send each copy once — this reddens.
     */
    @Test
    fun `a retryable refusal is retried for a copy that is not the first`() = runTest {
        val h = harness(devices = listOf(pinnedDevice))
        whenever(
            h.messaging.sendMessage(
                messageId = any(),
                senderId = any(),
                recipientId = any(),
                conversationId = any(),
                encryptedPayload = any(),
                timestampMs = any(),
                contentType = any(),
            ),
        ).thenReturn(
            MessagingService.SendResult("ok", false, "UNAVAILABLE", true, 0, "a"),
            MessagingService.SendResult("ok", true, "", true, 0, "b"),
        )

        val outcome = h.useCase()(peer, "hello")

        assertTrue(outcome is SendOutcome.Sent)
        assertEquals(2, sentMessageIds(h).size)
    }
}

private class FakeMessageDao : MessageDao {
    val rows = linkedMapOf<String, MessageEntity>()
    override fun observeChat(chatId: String) = MutableStateFlow(rows.values.filter { it.chatId == chatId })
    override suspend fun getById(messageId: String) = rows[messageId]
    override suspend fun getByIdIgnoreCase(messageId: String) =
        rows.entries.firstOrNull { it.key.equals(messageId, ignoreCase = true) }?.value
    override suspend fun insert(message: MessageEntity) { rows[message.id] = message }
    override suspend fun updateDeliveryStatus(messageId: String, status: String) {
        rows[messageId]?.let { rows[messageId] = it.copy(deliveryStatus = status) }
    }
    override suspend fun markEdited(id: String, text: String) {
        rows[id]?.let { rows[id] = it.copy(text = text, isEdited = true) }
    }
    override suspend fun deleteById(id: String) { rows.remove(id) }
    override suspend fun latestVisible(chatId: String) =
        rows.values.filter { it.chatId == chatId && it.contentType == 0 }.maxByOrNull { it.timestamp }
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
    override suspend fun setSecurityNotice(userId: String, code: Int) {
        rows[userId]?.let { rows[userId] = it.copy(securityNotice = code) }
    }
}
