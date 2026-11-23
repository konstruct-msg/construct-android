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
import com.construct.messenger.data.local.FakeServerMessageIdStore
import com.construct.messenger.service.OrchestratorGateway
import com.construct.messenger.service.OwedCopies
import com.construct.messenger.service.ServerMessageIds
import com.construct.messenger.util.IncomingPlaintext
import com.construct.messenger.util.KnstFrame
import com.construct.messenger.util.SenderSyncRouting
import com.construct.messenger.util.TextWire
import com.construct.messenger.util.ProfileShare
import com.construct.messenger.service.SessionManager
import com.construct.messenger.service.DeviceSetAnswers
import com.construct.messenger.stealth.SealedSend
import com.construct.messenger.stealth.StealthPolicy
import com.construct.messenger.stealth.StealthSenderService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import io.grpc.Status
import io.grpc.StatusException
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
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

    private val ownCertificate = shared.proto.core.v1.EnvelopeOuterClass.SenderCertificate.newBuilder()
        .setSenderUserId("me")
        .setSenderDeviceId("22222222222222222222222222222222")
        .build()
        .toByteArray()
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
        val serverIds = FakeServerMessageIdStore()
        val sessions: SessionStateStore = mock()
        val keystore: KeystoreManager = mock()
        val crypto: CryptoManager = mock()
        val sessionManager: SessionManager = mock()
        val orchestrator: OrchestratorGateway = mock()
        val messaging: MessagingService = mock()
        val policy: StealthPolicy = mock()
        val stealthSender: StealthSenderService = mock()
        val owed = RecordingOwedCopies()
        val sealedSend: SealedSend = mock()
        val deviceSets = DeviceSetAnswers()

        fun useCase() = SendMessageUseCase(
            keystoreManager = keystore,
            sessionManager = sessionManager,
            orchestrator = orchestrator,
            cryptoManager = crypto,
            messagingService = messaging,
            stealthPolicy = policy,
            stealthSender = stealthSender,
            sealedSend = sealedSend,
            messages = messages,
            chats = chats,
            contacts = users,
            sessionStateStore = sessions,
            serverMessageIds = ServerMessageIds(serverIds),
            mediaPreview = { "Photo" },
            owedCopies = owed,
            deviceSets = deviceSets,
        )
    }

    /** What the send told the retry queue (TODO 155). */
    private class RecordingOwedCopies : OwedCopies {
        val owed = mutableListOf<Pair<String, List<String>>>()
        val late = mutableListOf<List<String>>()
        val settled = mutableListOf<String>()

        override suspend fun owed(messageId: String, recipient: String, sender: String, devices: List<String>, late: List<String>) {
            owed += recipient to devices
            this.late += late
        }

        override suspend fun settled(messageId: String, recipient: String) {
            settled += recipient
        }
    }

    private suspend fun harness(
        devices: List<String> = listOf("11111111111111111111111111111111", "22222222222222222222222222222222"),
        accepted: Boolean = true,
    ): Harness {
        val h = Harness()
        whenever(h.keystore.getUserId()).thenReturn(myId)
        whenever(h.stealthSender.getSenderCertificate()).thenReturn(ownCertificate)
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
        // Nothing known about our own devices: the send asks the key server (`ownDeviceSet`).
        whenever(h.sessionManager.knownPeerDevices(myId)).thenReturn(emptyList())
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
     * Our own account is not a peer: no session is opened with it, nothing is sent and no row is
     * written. Until 2026-11-23 a send to it opened a session with this very device and came back
     * as a message from ourselves. Mutation: drop any one of the checks.
     */
    @Test
    fun `nothing is sent to our own account`() = runTest {
        val h = harness()
        whenever(h.sessionManager.ensureSession(myId)).thenReturn(SessionManager.SessionPeer(myId, ourDevice, byteArrayOf(1)))
        val refused = listOf(
            h.useCase()(myId, "note"),
            h.useCase().sendSticker(myId.uppercase(), com.construct.messenger.stickers.StickerReference.of(ByteArray(32) { 7 }, 0, "🙂")!!),
            h.useCase().react(myId, byteArrayOf(1)),
            h.useCase().deliverPrepared(myId, "row", 1L, byteArrayOf(1)),
        )
        assertTrue("$refused", refused.all { it is SendOutcome.Failed && it.reason == "our own account" })
        assertTrue(runCatching { h.useCase().persistMedia(myId, "m", 1L, com.construct.messenger.util.MediaWire.Stored("k", byteArrayOf(), ""), null) }.isFailure)
        assertEquals(ResendOutcome.NOT_OURS, h.useCase().resend(myId, ourDevice, "row"))
        assertTrue(h.messages.rows.isEmpty())
        assertTrue(h.chats.rows.isEmpty())
        verify(h.sessionManager, org.mockito.kotlin.never()).ensureSession(any())
        org.mockito.kotlin.verifyNoInteractions(h.orchestrator, h.messaging)
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
     * The certificate names the device that wrote the copy, and nothing else says which session it
     * is read on. Without one no copy is written and the ratchet does not move; the recipient's
     * copy still goes.
     */
    @Test
    fun `no copy goes to our own device without a certificate`() = runTest {
        val h = harness(devices = listOf(pinnedDevice))
        val ownDevice = "33333333333333333333333333333333"
        whenever(h.stealthSender.getSenderCertificate()).thenThrow(RuntimeException("offline"))
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

        assertTrue(framesSentTo(h, ownDevice).isEmpty())
        assertEquals(1, framesSentTo(h, pinnedDevice).size)
        verify(h.sessionManager, org.mockito.kotlin.never()).ensureSessionForDevice(myId, ownDevice)
    }

    /** A copy that goes without its certificate opens nothing on the sibling, in silence. */
    @Test
    fun `a copy to our own device carries our certificate`() = runTest {
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

        val payloads = argumentCaptor<ByteArray>()
        verify(h.messaging, org.mockito.kotlin.atLeastOnce()).sendMessage(
            any(), any(), eq(myId), any(), payloads.capture(), any(),
            eq(shared.proto.core.v1.EnvelopeOuterClass.ContentType.CONTENT_TYPE_SENDER_SYNC),
        )
        val copy = com.construct.messenger.stealth.OwnDeviceCopy.unwrap(payloads.lastValue)
        assertEquals("22222222222222222222222222222222", copy?.certificate?.deviceId)
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
     * The tester's report (2026-10-20): the chat list named a message the chat showed two or three
     * up. Ours, written before theirs arrived, reached the server after it — the transcript puts
     * ours last, and the list must too, though our clock says earlier. Mutation: no preview
     * refresh after `setOrderKey` — the list keeps naming theirs.
     */
    @Test
    fun `a message the server places last becomes the chat preview, whatever its clock`() = runTest {
        val h = harness(devices = listOf(pinnedDevice))
        val chatId = com.construct.messenger.util.ConversationId.direct(myId, peer)
        val mine = "55555555-5555-4555-8555-555555555555"
        h.messages.rows[mine] = MessageRecord(mine, chatId, "mine", true, 5L, DeliveryStatus.FAILED)
        h.messages.rows["theirs"] = MessageRecord("theirs", chatId, "theirs", false, 50L, DeliveryStatus.DELIVERED)
            .copy(orderKey = com.construct.messenger.util.ServerMessageOrder.key(1_000, 0)!!)
        h.chats.rows[chatId] = ChatRecord(chatId, peer, "theirs", 50L)
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
            MessagingService.SendResult("ok", true, "", false, 0, "a", com.construct.messenger.util.ServerMessageOrder.key(2_000, 0)),
        )

        val useCase = h.useCase()
        assertEquals(SendOutcome.Sent(mine), useCase.retry(peer, useCase.failedRow(peer, mine)!!))

        assertEquals("mine", h.chats.rows[chatId]?.lastMessageText)
        assertEquals(5L, h.chats.rows[chatId]?.lastMessageTimeMs)
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
     * The device set is the local one — a send to a peer we already hold sessions with must not
     * wait on the key server, whatever it would answer. The answer to the send corrects the set.
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
     * TODO 155: the device that refused is owed its copy — it alone; the one that took it is not.
     *
     * Mutation: owe every recipient target, or none — this reddens.
     */
    @Test
    fun `a device that refused its copy is owed it, and only that device`() = runTest {
        val h = harness()
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
        ).thenAnswer { invocation ->
            val took = !(invocation.arguments[0] as String).contains(siblingDevice.take(16))
            MessagingService.SendResult("ok", took, if (took) "" else "PERMISSION_DENIED", false, 0, "a")
        }

        val outcome = h.useCase()(peer, "hello")

        assertTrue(outcome is SendOutcome.Sent)
        assertEquals(listOf(peer to listOf(siblingDevice)), h.owed.owed)
        // Refused, not late: a device that refuses does not hold the chat (todo 321). Mutation: every owed device late.
        assertEquals(listOf(emptyList<String>()), h.owed.late)
        assertTrue(h.owed.settled.isEmpty())
    }

    @Test
    fun `a message every device took owes nothing`() = runTest {
        val h = harness()

        h.useCase()(peer, "hello")

        assertTrue(h.owed.owed.isEmpty())
        assertEquals(listOf(peer), h.owed.settled)
    }

    /** A send no device took is FAILED and retried whole by the user — not the queue's. */
    @Test
    fun `a failed send owes nothing to the queue`() = runTest {
        val h = harness(accepted = false)

        h.useCase()(peer, "hello")

        assertTrue(h.owed.owed.isEmpty())
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
     * Todo 305: a send whose recipient device is being opened by another send waits — QUEUED, its
     * copy owed to that device for the open's answer — rather than FAILED, as the second album of
     * a share was on stand 304. Mutation: take `OpenInFlight` as any session failure — the row is
     * FAILED and nothing is owed.
     */
    @Test
    fun `a send that waits for another send's open is queued, not failed`() = runTest {
        val h = harness(devices = listOf(pinnedDevice))
        whenever(h.sessionManager.ensureSessionForDevice(peer, pinnedDevice))
            .thenThrow(SessionManager.OpenInFlight(pinnedDevice))

        val outcome = h.useCase()(peer, "hello")

        assertTrue(outcome is SendOutcome.Queued)
        assertEquals(DeliveryStatus.QUEUED, h.messages.rows[(outcome as SendOutcome.Queued).messageId]?.deliveryStatus)
        assertEquals(listOf(peer to listOf(pinnedDevice)), h.owed.owed)
    }

    /**
     * Todo 321: a copy the path cut off — the channel shut under it when the stream was declared
     * dead — got no answer about the message. It waits (QUEUED), owed to that device, and the same
     * ciphertext goes again; FAILED made the person send it again, after the ones written later.
     * The decision is the core's (`after_send_failure`), as on iOS. Mutation: fold a cut copy as
     * any failure — the row is FAILED and nothing is owed.
     */
    @Test
    fun `a copy the path cut off is queued, not failed`() = runTest {
        val h = harness(devices = listOf(pinnedDevice))
        whenever(
            h.messaging.sendMessage(
                messageId = any(), senderId = any(), recipientId = any(), conversationId = any(),
                encryptedPayload = any(), timestampMs = any(), contentType = any(),
            ),
        ).thenAnswer { throw StatusException(Status.CANCELLED.withDescription("Channel is forcefully shutdown")) }

        val outcome = h.useCase()(peer, "hello")

        assertTrue(outcome is SendOutcome.Queued)
        assertEquals(DeliveryStatus.QUEUED, h.messages.rows[(outcome as SendOutcome.Queued).messageId]?.deliveryStatus)
        assertEquals(listOf(peer to listOf(pinnedDevice)), h.owed.owed)
        assertEquals(listOf(listOf(pinnedDevice)), h.owed.late)
    }

    /**
     * Todo 321: the bubble is in the chat before the send's turn comes, and no copy leaves until it
     * does. Mutations: write the row inside the turn — nothing shows while it waits; send outside
     * the turn — a copy leaves ahead of the ones before it.
     */
    @Test
    fun `a text is in the chat before its turn, and leaves only on it`() = runTest {
        val h = harness(devices = listOf(pinnedDevice))
        var waited: DeliveryStatus? = null

        val outcome = h.useCase()(peer, "hello", null) { leave ->
            waited = h.messages.rows.values.single { it.text == "hello" }.deliveryStatus
            verify(h.orchestrator, org.mockito.kotlin.never()).handleEvent(any<uniffi.construct_core.CfeIncomingEvent.OutgoingMessage>())
            leave()
        }

        assertEquals(DeliveryStatus.SENDING, waited)
        assertTrue(outcome is SendOutcome.Sent)
        assertEquals(1, framesSentTo(h, pinnedDevice).size)
    }

    /** A sticker too. Mutation: send it outside its turn. */
    @Test
    fun `a sticker is in the chat before its turn, and leaves only on it`() = runTest {
        val h = harness(devices = listOf(pinnedDevice))
        val ref = com.construct.messenger.stickers.StickerReference.of(ByteArray(32) { 7 }, 0, "🙂")!!
        var waited: DeliveryStatus? = null

        val outcome = h.useCase().sendSticker(peer, ref) { leave ->
            waited = h.messages.rows.values.single { it.mediaType == com.construct.messenger.util.MediaWire.KIND_STICKER }.deliveryStatus
            verify(h.orchestrator, org.mockito.kotlin.never()).handleEvent(any<uniffi.construct_core.CfeIncomingEvent.OutgoingMessage>())
            leave()
        }

        assertEquals(DeliveryStatus.SENDING, waited)
        assertTrue(outcome is SendOutcome.Sent)
    }

    /** The media path: written and uploaded before, sent on the turn. Mutation: leave the turn out of `deliverPrepared`. */
    @Test
    fun `a prepared message leaves only on its turn`() = runTest {
        val h = harness(devices = listOf(pinnedDevice))
        val chatId = com.construct.messenger.util.ConversationId.direct(myId, peer)
        val id = "33333333-3333-4333-8333-333333333333"
        h.messages.rows[id] = MessageRecord(id, chatId, "hi", true, 1L, DeliveryStatus.SENDING)
        var turns = 0

        val outcome = h.useCase().deliverPrepared(peer, id, 1L, TextWire.encode("hi", null)) { leave ->
            turns++
            verify(h.orchestrator, org.mockito.kotlin.never()).handleEvent(any<uniffi.construct_core.CfeIncomingEvent.OutgoingMessage>())
            leave()
        }

        assertEquals(1, turns)
        assertTrue(outcome is SendOutcome.Sent)
    }

    /**
     * A status that is the server's answer about the message fails it. Mutation: queue every
     * thrown error — a refused message would wait for good.
     */
    @Test
    fun `a copy the server refused fails the message`() = runTest {
        val h = harness(devices = listOf(pinnedDevice))
        whenever(
            h.messaging.sendMessage(
                messageId = any(), senderId = any(), recipientId = any(), conversationId = any(),
                encryptedPayload = any(), timestampMs = any(), contentType = any(),
            ),
        ).thenAnswer { throw StatusException(Status.PERMISSION_DENIED) }

        val outcome = h.useCase()(peer, "hello")

        assertTrue(outcome is SendOutcome.Failed)
        assertEquals(DeliveryStatus.FAILED, h.messages.rows[(outcome as SendOutcome.Failed).messageId]?.deliveryStatus)
        assertTrue(h.owed.owed.isEmpty())
    }

    /** Another device took it: sent, and the waiting one is owed. Mutation: owe only refusals. */
    @Test
    fun `a device whose open is running is owed its copy when another took it`() = runTest {
        val h = harness()
        whenever(h.sessionManager.ensureSessionForDevice(peer, siblingDevice))
            .thenThrow(SessionManager.OpenInFlight(siblingDevice))

        val outcome = h.useCase()(peer, "hello")

        assertTrue(outcome is SendOutcome.Sent)
        assertEquals(listOf(peer to listOf(siblingDevice)), h.owed.owed)
        // On its way, so the next message waits for it (todo 321). Mutation: no late devices on a sent message.
        assertEquals(listOf(listOf(siblingDevice)), h.owed.late)
    }

    /**
     * The waiting copy taken is the message in the mailbox: QUEUED becomes SENT. Mutation: leave
     * the row as it was — it stays queued for good.
     */
    @Test
    fun `a queued message is sent once its waiting copy is taken`() = runTest {
        val h = harness(devices = listOf(pinnedDevice))
        val id = "55555555-5555-4555-8555-555555555555"
        h.messages.rows[id] = MessageRecord(id, com.construct.messenger.util.ConversationId.direct(myId, peer), "waited", true, 5L, DeliveryStatus.QUEUED)

        assertEquals(ResendOutcome.SENT, h.useCase().resend(peer, pinnedDevice, id))

        assertEquals(DeliveryStatus.SENT, h.messages.rows[id]?.deliveryStatus)
    }

    /**
     * The retry gave up: a message no device took is FAILED; one being sent again right now (the
     * person pressed retry) is left to that send. Mutation: fail every row given up — the sending
     * one reddens. (A SENT row is held by the store's evidence rank whatever this does.)
     */
    @Test
    fun `a queued message the retry gave up on is failed, one being sent stays`() = runTest {
        val h = harness()
        val chatId = com.construct.messenger.util.ConversationId.direct(myId, peer)
        val queued = "66666666-6666-4666-8666-666666666666"
        val sending = "77777777-7777-4777-8777-777777777777"
        h.messages.rows[queued] = MessageRecord(queued, chatId, "a", true, 5L, DeliveryStatus.QUEUED)
        h.messages.rows[sending] = MessageRecord(sending, chatId, "b", true, 6L, DeliveryStatus.SENDING)

        h.useCase().givenUp(queued)
        h.useCase().givenUp(sending)

        assertEquals(DeliveryStatus.FAILED, h.messages.rows[queued]?.deliveryStatus)
        assertEquals(DeliveryStatus.SENDING, h.messages.rows[sending]?.deliveryStatus)
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
     * The server's answer to a limit names when the window ends, up to an hour on. That wait does
     * not belong inside the send (todo/280). The contact's copy goes first, so here the message
     * was in their mailbox while its row said sending for three hours: an hour, then two.
     *
     * Mutation: wait out `retryAfterMs` again — this reddens.
     */
    @Test
    fun `a limit on our own copy does not hold the message up`() = runTest {
        val h = harness(devices = listOf(pinnedDevice))
        val ownDevice = withOwnDevice(h)
        whenever(
            h.messaging.sendMessage(
                messageId = any(),
                senderId = any(),
                recipientId = eq(myId),
                conversationId = any(),
                encryptedPayload = any(),
                timestampMs = any(),
                contentType = any(),
            ),
        ).thenReturn(rateLimited)

        val outcome = h.useCase()(peer, "hello")

        assertTrue(outcome is SendOutcome.Sent)
        assertEquals(0L, currentTime)
        assertEquals(1, framesSentTo(h, ownDevice).size)
    }

    /** The next device does not wait for the one that was refused, and the refused one is owed. */
    @Test
    fun `a limit on one device does not hold up the next`() = runTest {
        val h = harness(devices = listOf(pinnedDevice, siblingDevice))
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
        ).thenReturn(rateLimited, MessagingService.SendResult("ok", true, "", true, 0, "b"))

        val outcome = h.useCase()(peer, "hello")

        assertTrue(outcome is SendOutcome.Sent)
        assertEquals(0L, currentTime)
        assertEquals(listOf(peer to listOf(pinnedDevice)), h.owed.owed)
    }

    /** Nobody took it: the row says failed now, and Retry is the person's. */
    @Test
    fun `a limit on the only copy fails the message at once`() = runTest {
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
        ).thenReturn(rateLimited)

        val outcome = h.useCase()(peer, "hello")

        assertTrue(outcome is SendOutcome.Failed)
        assertEquals(0L, currentTime)
        assertEquals(1, sentMessageIds(h).size)
    }

    /**
     * The control: a refusal with no time named is still tried again, after the short pause.
     * Mutation: never retry a refusal — this reddens.
     */
    @Test
    fun `a refusal naming no time is tried again shortly`() = runTest {
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
        assertEquals(400L, currentTime)
    }

    private val rateLimited = MessagingService.SendResult("ok", false, "RATE_LIMIT", true, 3_600_000, "a")

    private suspend fun withOwnDevice(h: Harness): String {
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
        return ownDevice
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

        assertEquals(sent.messageId.lowercase(), h.serverIds.rows["server-id-1"]?.first)
        assertEquals(ResendOutcome.SENT, h.useCase().resend(peer, pinnedDevice, "SERVER-ID-1"))
    }

    @Test
    fun `an id the table does not hold is taken as our own`() = runTest {
        val ids = ServerMessageIds(FakeServerMessageIdStore())
        assertEquals("abc-fd-1", ids.localId("abc-fd-1"))
    }

    /** Older than the server keeps a queue, no error can name it. Mutation: never prune — this reddens. */
    @Test
    fun `pairs older than the queue are dropped`() = runTest {
        val store = FakeServerMessageIdStore()
        store.record("old", "m1", recordedAtMs = 0)
        ServerMessageIds(store).record("new", "m2", nowMs = ServerMessageIds.RETENTION_MS + 1)
        assertEquals(setOf("new"), store.rows.keys)
    }

    /**
     * Read markers are planned for this account alone: no recipient devices, `recipient_is_self`
     * (TODO 153). With no other device of ours the plan is empty and nothing goes. Mutation: plan
     * them as an ordinary send to anyone — reddens.
     */
    @Test
    fun `read markers are planned for our own other devices only`() = runTest {
        val h = harness()
        whenever(h.crypto.planSend(any(), any(), any(), any())).thenReturn(emptyList())

        assertTrue("one device: nothing owed, nothing to retry", h.useCase().sendReadMarkers(byteArrayOf(1, 2, 3)))

        verify(h.crypto).planSend(eq(emptyList()), any(), eq(ourDevice), eq(true))
        verify(h.sessionManager).discoverOwnDeviceBundles(myId)
    }

    /** Mutation: drop the skip — every publish would hand the key again to devices that have it. */
    @Test
    fun `the account's key goes only to our own devices that do not have it`() = runTest {
        val h = harness()
        val ownDevice = "33333333333333333333333333333333"
        val second = "c".repeat(32)
        whenever(h.sessionManager.discoverOwnDeviceBundles(myId)).thenReturn(
            listOf(
                SessionManager.PeerBundle(myId, ownDevice, byteArrayOf(8), bundle = mock<BinaryKeyBundle>()),
                SessionManager.PeerBundle(myId, second, byteArrayOf(9), bundle = mock<BinaryKeyBundle>()),
            ),
        )
        whenever(h.crypto.planSend(any(), any(), any(), any())).thenReturn(emptyList())

        assertTrue(h.useCase().sendOwnContactCard(byteArrayOf(1), skipping = setOf(ownDevice.lowercase())).isEmpty())

        verify(h.crypto).planSend(eq(emptyList()), eq(listOf(second)), eq(ourDevice), eq(true))
    }

    /**
     * Todo 321, step 3: a send to someone whose devices this device knows does not ask the key
     * server first. On a bad path that request held every message for up to 30 s.
     *
     * Mutation: ask the directory before every send again — this reddens.
     */
    @Test
    fun `a send to known devices does not wait on the key server`() = runTest {
        val h = harness()

        assertTrue(h.useCase()(peer, "hello") is SendOutcome.Sent)

        verify(h.sessionManager, times(0)).discoverPeerBundles(any(), any())
    }

    /** Stealth on, every sealed copy answering with [named] as the recipient's devices. */
    private suspend fun sealedHarness(named: List<String>): Harness {
        val h = harness(devices = listOf(pinnedDevice))
        whenever(h.policy.shouldUseSealedSender()).thenReturn(true)
        whenever(h.sealedSend.send(any(), any(), any(), any(), anyOrNull())).thenReturn(
            MessagingService.SendResult("ok", true, "", false, 0, "a", recipientActiveDevices = named),
        )
        return h
    }

    /**
     * The answer names a device the plan did not have: the key server is asked, and the new
     * device gets its copy of this very message. The same set is not asked about again.
     *
     * Mutations that redden it: ignore the answers; send nothing to the found device; forget the
     * set asked about.
     */
    @Test
    fun `a device the answer names gets this message, and the set is asked about once`() = runTest {
        val newDevice = "44444444444444444444444444444444"
        val h = sealedHarness(named = listOf(newDevice, pinnedDevice))
        whenever(h.sessionManager.discoverPeerBundles(peer)).thenReturn(
            listOf(pinnedDevice, newDevice).map {
                SessionManager.PeerBundle(peer, it, byteArrayOf(4, 5, 6), bundle = mock<BinaryKeyBundle>())
            },
        )
        whenever(h.sessionManager.ensureSessionForDevice(peer, newDevice))
            .thenReturn(SessionManager.SessionPeer(peer, newDevice, byteArrayOf(4, 5, 6)))
        whenever(h.crypto.deviceCopyTag(any(), eq(newDevice), any())).thenReturn(newDevice.take(16))

        assertTrue(h.useCase()(peer, "hello") is SendOutcome.Sent)

        verify(h.sessionManager, times(1)).discoverPeerBundles(peer)
        assertEquals(1, framesSentTo(h, newDevice).size)
        assertEquals(1, framesSentTo(h, pinnedDevice).size)

        assertTrue(h.useCase()(peer, "again") is SendOutcome.Sent)
        verify(h.sessionManager, times(1)).discoverPeerBundles(peer)
    }

    /**
     * An answer naming exactly the plan asks nothing. Mutation: ask whenever the answer names
     * anything — this reddens.
     */
    @Test
    fun `an answer naming the planned set asks nothing`() = runTest {
        val h = sealedHarness(named = listOf(pinnedDevice))

        assertTrue(h.useCase()(peer, "hello") is SendOutcome.Sent)

        verify(h.sessionManager, times(0)).discoverPeerBundles(any(), any())
    }

    /**
     * The key server does not answer: the devices the answer named and the plan lacked are owed,
     * so the retry queue sends them this message, and the set is not marked as asked.
     *
     * Mutation: drop the owed devices on a failed ask — this reddens.
     */
    @Test
    fun `a named device the key server does not hand over is owed`() = runTest {
        val newDevice = "44444444444444444444444444444444"
        val h = sealedHarness(named = listOf(newDevice, pinnedDevice))
        whenever(h.sessionManager.discoverPeerBundles(peer)).thenThrow(RuntimeException("offline"))

        assertTrue(h.useCase()(peer, "hello") is SendOutcome.Sent)

        assertEquals(listOf(peer to listOf(newDevice)), h.owed.owed)
        assertTrue(h.deviceSets.toAsk(peer, listOf(listOf(newDevice, pinnedDevice)), listOf(pinnedDevice)) != null)
    }
}
