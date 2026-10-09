package com.construct.messenger.domain.usecase

import com.construct.messenger.crypto.CryptoManager
import com.construct.messenger.data.api.MessagingService
import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.data.local.SessionStateStore
import com.construct.messenger.data.local.FakeChatStore
import com.construct.messenger.data.local.ChatRecord
import com.construct.messenger.data.local.FakeMessageStore
import com.construct.messenger.data.local.MessageRecord
import com.construct.messenger.data.local.FakeContactStore
import com.construct.messenger.data.local.ContactRecord
import com.construct.messenger.data.model.DeliveryStatus
import com.construct.messenger.data.local.db.ServerMessageIdDao
import com.construct.messenger.data.local.db.ServerMessageIdEntity
import com.construct.messenger.service.OrchestratorGateway
import com.construct.messenger.service.ServerMessageIds
import com.construct.messenger.util.IncomingPlaintext
import com.construct.messenger.util.KnstFrame
import com.construct.messenger.util.SenderSyncRouting
import com.construct.messenger.util.TextWire
import com.construct.messenger.util.ProfileShare
import com.construct.messenger.service.SessionManager
import com.construct.messenger.stealth.StealthPolicy
import com.construct.messenger.stealth.StealthSenderService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        val messages = FakeMessageStore()
        val chats = FakeChatStore()
        val users = FakeContactStore()
        val serverIds = FakeServerMessageIdDao()
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
            sealedSend = mock(),
            messages = messages,
            chats = chats,
            contacts = users,
            sessionStateStore = sessions,
            serverMessageIds = ServerMessageIds(serverIds),
            mediaPreview = { "Photo" },
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

    private suspend fun framesSentTo(h: Harness, device: String): List<KnstFrame.Chunk> {
        val events = argumentCaptor<uniffi.construct_core.CfeIncomingEvent>()
        verify(h.orchestrator, org.mockito.kotlin.atLeastOnce()).handleEvent(events.capture())
        return events.allValues
            .filterIsInstance<uniffi.construct_core.CfeIncomingEvent.OutgoingMessage>()
            .filter { it.contactId == device }
            .map { KnstFrame.parse(it.plaintext)!! }
    }

    /**
     * A text over one frame goes as several to each device, named `-c<n>` as iOS names them, and
     * joins back into the text. Mutation: `KnstFrame.pack` instead of `chunks` — the send throws.
     */
    @Test
    fun `a long text goes as numbered frames to every device`() = runTest {
        val h = harness()
        val long = "x".repeat(5000)
        val outcome = h.useCase()(peer, long)
        assertTrue(outcome is SendOutcome.Sent)

        val ids = sentMessageIds(h)
        assertEquals(4, ids.size)
        assertTrue(ids.any { it.endsWith("-fd-" + pinnedDevice.take(16) + "-c0") })
        assertTrue(ids.any { it.endsWith("-fd-" + pinnedDevice.take(16) + "-c1") })
        val frames = framesSentTo(h, pinnedDevice)
        assertEquals(listOf(0, 1), frames.map { it.index })
        val joined = frames.flatMap { it.payload.toList() }.toByteArray()
        assertEquals(TextWire.encode(long).toList(), joined.toList())
    }

    /**
     * Our own device gets what iOS sends it: type-23 frames around `SSR1 ‖ content`. Android put
     * `SSR1` outside the frame, and an iOS sibling dropped the copy as unroutable. Mutation: frame
     * the replica like a recipient copy — this reddens.
     */
    @Test
    fun `our own device gets the partner inside a sender-sync frame`() = runTest {
        val h = harness(devices = listOf(pinnedDevice))
        val ownDevice = "33333333333333333333333333333333"
        whenever(h.sessionManager.discoverOwnDeviceBundles(myId)).thenReturn(
            listOf(SessionManager.PeerBundle(myId, ownDevice, byteArrayOf(8), bundle = mock<BinaryKeyBundle>())),
        )
        whenever(h.sessionManager.ensureSessionForDevice(myId, ownDevice))
            .thenReturn(SessionManager.SessionPeer(myId, ownDevice, byteArrayOf(8)))
        whenever(h.crypto.deviceCopyTag(any(), eq(ownDevice), any())).thenReturn("ownownownownown0")
        whenever(h.crypto.planSend(any(), any(), any(), any())).thenReturn(
            listOf(
                DeliveryTarget(pinnedDevice, DeliveryAudience.RECIPIENT),
                DeliveryTarget(ownDevice, DeliveryAudience.OWN_REPLICA),
            ),
        )

        h.useCase()(peer, "hello")

        val frame = framesSentTo(h, ownDevice).single()
        assertEquals(KnstFrame.TYPE_SENDER_SYNC, frame.contentType)
        val routed = SenderSyncRouting.decode(frame.payload)!!
        assertEquals(peer, routed.partnerUserId)
        assertEquals(TextWire.encode("hello").toList(), routed.payload.toList())
        assertEquals(KnstFrame.TYPE_E2EE_SIGNAL, framesSentTo(h, pinnedDevice).single().contentType)
    }


    /**
     * A photo message can be sent again after a decryption error: its row keeps the wire album.
     * One still uploading cannot — its ids name nothing in the store. Mutation: resend text only
     * (the old guard) — the first case reddens.
     */
    @Test
    fun `a photo message is resent from its row, not before its upload`() = runTest {
        val h = harness(devices = listOf(pinnedDevice))
        fun album(id: String) = shared.proto.messaging.v1.Content.MediaAlbumMessage.newBuilder().addItems(
            shared.proto.messaging.v1.Content.MediaMessage.newBuilder()
                .setMediaId(id)
                .setEncryptionKey(com.google.protobuf.ByteString.copyFrom(ByteArray(32)))
                .setMimeType("image/jpeg"),
        ).build().toByteArray()
        val chatId = com.construct.messenger.util.ConversationId.direct(myId, peer)
        val sent = "11111111-1111-4111-8111-111111111111"
        val staged = "22222222-2222-4222-8222-222222222222"
        h.messages.rows[sent] = MessageRecord(sent, chatId, "", true, 1L, DeliveryStatus.SENT, mediaType = "album", mediaPayload = album("store-id"))
        h.messages.rows[staged] = MessageRecord(staged, chatId, "", true, 1L, DeliveryStatus.SENDING, mediaType = "album", mediaPayload = album("local-x"))

        assertEquals(ResendOutcome.SENT, h.useCase().resend(peer, pinnedDevice, sent))
        val frame = framesSentTo(h, pinnedDevice).single()
        assertEquals("store-id", shared.proto.messaging.v1.Content.MessageContent.parseFrom(frame.payload).mediaAlbum.getItems(0).mediaId)
        assertEquals(ResendOutcome.NOT_OURS, h.useCase().resend(peer, pinnedDevice, staged))
    }

    /**
     * Retry sends the failed row again under its own id and its status follows; a message that
     * did go, or a peer's, is not retried. Mutation: drop the FAILED test in `failedRow` — a sent
     * message goes out a second time.
     */
    @Test
    fun `retry sends a failed message again under its id, and nothing else`() = runTest {
        val h = harness(devices = listOf(pinnedDevice))
        val chatId = com.construct.messenger.util.ConversationId.direct(myId, peer)
        val failed = "33333333-3333-4333-8333-333333333333"
        val sent = "44444444-4444-4444-8444-444444444444"
        h.messages.rows[failed] = MessageRecord(failed, chatId, "again", true, 5L, DeliveryStatus.FAILED)
        h.messages.rows[sent] = MessageRecord(sent, chatId, "once", true, 6L, DeliveryStatus.SENT)

        val useCase = h.useCase()
        assertNull(useCase.failedRow(peer, sent))
        val outcome = useCase.retry(peer, useCase.failedRow(peer, failed)!!)

        assertEquals(SendOutcome.Sent(failed), outcome)
        assertEquals(DeliveryStatus.SENT, h.messages.rows[failed]?.deliveryStatus)
        val frame = framesSentTo(h, pinnedDevice).single()
        assertEquals(java.util.UUID.fromString(failed), frame.messageId)
        assertEquals("again", shared.proto.messaging.v1.Content.MessageContent.parseFrom(frame.payload).text.text)
    }

    /**
     * iOS edits a photo's caption with `new_text`; the row keeps the album with the new caption, so
     * a resend carries what the bubble shows. Mutation: `markEdited` for every row — the album
     * keeps the old caption.
     */
    @Test
    fun `editing a photo caption rewrites the album it is resent from`() = runTest {
        val h = harness(devices = listOf(pinnedDevice))
        val chatId = com.construct.messenger.util.ConversationId.direct(myId, peer)
        val id = "55555555-5555-4555-8555-555555555555"
        val album = shared.proto.messaging.v1.Content.MediaAlbumMessage.newBuilder()
            .addItems(shared.proto.messaging.v1.Content.MediaMessage.newBuilder().setMediaId("store-id").setMimeType("image/jpeg"))
            .setCaption("old")
            .build().toByteArray()
        h.messages.rows[id] = MessageRecord(id, chatId, "old", true, 1L, DeliveryStatus.SENT, mediaType = "album", mediaPayload = album)

        assertTrue(h.useCase().edit(peer, id, "new") is SendOutcome.Sent)

        val row = h.messages.rows[id]!!
        assertEquals("new", row.text)
        assertTrue(row.isEdited)
        assertEquals("new", shared.proto.messaging.v1.Content.MediaAlbumMessage.parseFrom(row.mediaPayload).caption)
        val edit = shared.proto.messaging.v1.Content.MessageContent.parseFrom(framesSentTo(h, pinnedDevice).single().payload).edit
        assertEquals("new", edit.newText.text)
    }

    @Test
    fun `a successful send persists SENT`() = runTest {
        val h = harness()
        val outcome = h.useCase()(peer, "hello")

        assertTrue(outcome is SendOutcome.Sent)
        val id = (outcome as SendOutcome.Sent).messageId
        assertEquals(DeliveryStatus.SENT, h.messages.rows[id]?.deliveryStatus)
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
        assertEquals(DeliveryStatus.FAILED, h.messages.rows[failed.messageId]?.deliveryStatus)
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
        assertEquals(DeliveryStatus.FAILED, h.messages.rows[failed.messageId]?.deliveryStatus)
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

    /**
     * B8: our profile goes to every device of theirs as a type-29 frame holding the `ProfileShare`,
     * not to our own devices, and leaves no row. Mutation: frame it as type 1 — this reddens.
     */
    @Test
    fun `a shared profile reaches every device of theirs and no one else`() = runTest {
        val h = harness()
        val events = argumentCaptor<uniffi.construct_core.CfeIncomingEvent>()

        val profile = ProfileShare("jolly mammoth", 5, ProfileShare.Avatar.Removed)
        val ok = h.useCase().shareProfile(peer, profile.encoded())

        assertTrue(ok)
        assertEquals(2, sentMessageIds(h).size)
        verify(h.orchestrator, times(2)).handleEvent(events.capture())
        events.allValues.forEach { event ->
            val plaintext = (event as uniffi.construct_core.CfeIncomingEvent.OutgoingMessage).plaintext
            assertEquals(29, plaintext[5].toInt())
            assertEquals(profile, KnstFrame.parse(plaintext)?.body()?.let(ProfileShare::read))
        }
        verify(h.sessionManager, times(0)).discoverOwnDeviceBundles(any())
        assertTrue(h.messages.rows.isEmpty())
    }

    /**
     * B1: the recipient reads its queue whenever it next comes online, so its DECRYPTION_ERROR
     * often reaches a sender that has restarted since. It names the server's id for the copy;
     * the pair must outlive the process. A new [SendMessageUseCase] over the same table is the
     * restart.
     *
     * Mutation: keep the map in memory again — this reddens.
     */
    @Test
    fun `a resend after a restart finds the message by the server's id`() = runTest {
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
        ).thenReturn(MessagingService.SendResult("SERVER-ID-1", true, "", false, 0, "a"))
        val sent = h.useCase()(peer, "hello") as SendOutcome.Sent

        assertEquals(sent.messageId.lowercase(), h.serverIds.rows["server-id-1"]?.localId)
        assertEquals(ResendOutcome.SENT, h.useCase().resend(peer, pinnedDevice, "SERVER-ID-1"))
    }

    @Test
    fun `an id the table does not hold is taken as our own`() = runTest {
        val ids = ServerMessageIds(FakeServerMessageIdDao())
        assertEquals("abc-fd-1", ids.localId("abc-fd-1"))
    }

    /** Older than the server keeps a queue, no error can name it. Mutation: never prune — this reddens. */
    @Test
    fun `pairs older than the queue are dropped`() = runTest {
        val dao = FakeServerMessageIdDao()
        dao.upsert(ServerMessageIdEntity("old", "m1", recordedAtMs = 0))
        ServerMessageIds(dao).record("new", "m2", nowMs = ServerMessageIds.RETENTION_MS + 1)
        assertEquals(setOf("new"), dao.rows.keys)
    }
}

private class FakeServerMessageIdDao : ServerMessageIdDao {
    val rows = linkedMapOf<String, ServerMessageIdEntity>()
    override suspend fun upsert(entry: ServerMessageIdEntity) { rows[entry.serverId] = entry }
    override suspend fun localId(serverId: String) = rows[serverId]?.localId
    override suspend fun pruneOlderThan(thresholdMs: Long): Int {
        val old = rows.values.filter { it.recordedAtMs < thresholdMs }.map { it.serverId }
        old.forEach(rows::remove)
        return old.size
    }
}

