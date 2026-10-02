package com.construct.messenger.service

import com.construct.messenger.crypto.CryptoManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import shared.proto.core.v1.EnvelopeOuterClass.ContentType
import uniffi.construct_core.CfeAction
import uniffi.construct_core.CfeIncomingEvent
import uniffi.construct_core.CfeSecureStoreSlot
import uniffi.construct_core.SenderCertificate

class MessageProcessorTest {

    private val sessionManager: SessionManager = mock()
    private val timerBridge: CfeTimerBridge = mock()
    private val cryptoManager: CryptoManager = mock()
    private val held = HeldEnvelopes()

    private class FakeGateway(
        var responses: MutableList<List<CfeAction>> = mutableListOf(),
    ) : OrchestratorGateway {
        val events = mutableListOf<CfeIncomingEvent>()
        override fun handleEvent(event: CfeIncomingEvent): List<CfeAction> {
            events += event
            return if (responses.isNotEmpty()) responses.removeAt(0) else emptyList()
        }
    }

    private class RecordingEffects : ProcessorEffects {
        val calls = mutableListOf<String>()
        var ackedInDb = false
        override suspend fun onDecrypted(contactId: String, messageId: String, plaintext: ByteArray) {
            calls += "onDecrypted:$contactId:$messageId"
        }
        override suspend fun onCallSignal(contactId: String, messageId: String, protoBytes: ByteArray) { calls += "onCallSignal:$messageId" }
        override suspend fun onControlFrame(contactId: String, messageId: String, contentType: Int, body: ByteArray) { calls += "onControlFrame:$contentType:$messageId" }
        override suspend fun sendReceipt(messageId: String, toUserId: String, status: String) { calls += "receipt:$messageId:$status" }
        override suspend fun notifyNewMessage(chatId: String, preview: String) { calls += "notify:$chatId" }
        override suspend fun markDelivered(messageId: String) { calls += "markDelivered:$messageId" }
        override suspend fun markProcessed(messageId: String, senderId: String) { calls += "markProcessed:$messageId" }
        override suspend fun saveSecureStore(slot: CfeSecureStoreSlot, data: ByteArray) { calls += "saveSecureStore:$slot" }
        override suspend fun pruneAckStore(cutoffTs: Long) { calls += "prune:$cutoffTs" }
        override suspend fun archiveSession(contactId: String) { calls += "archive:$contactId" }
        override suspend fun onSenderSync(contactId: String, messageId: String, plaintext: ByteArray, timestampMs: Long) {
            calls += "onSenderSync:$contactId:$messageId"
        }
        override suspend fun sendDecryptionError(contactId: String, messageId: String, payload: ByteArray, enveloped: Boolean) {
            calls += "decryptionError:$contactId:$messageId" + if (enveloped) ":enveloped" else ""
        }
        override suspend fun sessionRetired(contactId: String, withoutOneTimePrekey: Boolean) {
            calls += "retired:$contactId:$withoutOneTimePrekey"
        }
        override suspend fun resendMessage(contactId: String, messageId: String) {
            calls += "resend:$contactId:$messageId"
        }
        var openOutcome = ProcessingOutcome.Processed
        override suspend fun openReceiving(
            device: String,
            trigger: MessageRouter.IncomingMessage?,
        ): ProcessingOutcome {
            calls += "open:$device"
            return openOutcome
        }
        override suspend fun release(messageIds: List<String>) { calls += "release:${messageIds.joinToString(",")}" }
        override fun isAckedInDb(messageId: String): Boolean = ackedInDb
    }

    private fun incoming(id: String = "m1", sender: String = "alice") = MessageRouter.IncomingMessage(
        messageId = id,
        senderId = sender,
        contentType = ContentType.CONTENT_TYPE_E2EE_SIGNAL,
        // Opaque to this layer: the Rust core owns wire parsing, the processor
        // forwards the blob untouched — any bytes work against the fake gateway.
        encryptedPayload = byteArrayOf(1, 2, 3),
        timestampMs = 1_000L,
        viaSealedSender = false,
    )

    // ── route() branches (pure) ──────────────────────────────────────────

    @Test
    fun `messageDecrypted executes side effects and reports Processed`() = runBlocking {
        val effects = RecordingEffects()
        val processor = MessageProcessor(FakeGateway(), effects, sessionManager, timerBridge, cryptoManager, held, mock())
        val actions = listOf(
            CfeAction.MessageDecrypted("alice", "m1", byteArrayOf(7)),
            CfeAction.SendReceipt("m1", "delivered"),
            CfeAction.NotifyNewMessage("chat1", "hi"),
        )

        val outcome = processor.route(actions, incoming())

        assertEquals(ProcessingOutcome.Processed, outcome)
        assertTrue(effects.calls.contains("onDecrypted:alice:m1"))
        assertTrue(effects.calls.contains("receipt:m1:delivered"))
        assertTrue(effects.calls.contains("notify:chat1"))
    }

    /**
     * A control frame the core named (core 0.30) goes to its handler and is not receipted — a
     * receipt for a receipt would never end. Mutation: route it like a call signal, with a
     * receipt — this reddens.
     */
    @Test
    fun `a control frame is handled and not receipted`() = runBlocking {
        val effects = RecordingEffects()
        val processor = MessageProcessor(FakeGateway(), effects, sessionManager, timerBridge, cryptoManager, held, mock())

        val outcome = processor.route(listOf(CfeAction.ControlFrameDecrypted("alice", "m1", 14u, byteArrayOf(1))), incoming())

        assertEquals(ProcessingOutcome.Processed, outcome)
        assertTrue(effects.calls.contains("onControlFrame:14:m1"))
        assertTrue(effects.calls.none { it.startsWith("receipt:") })
    }

    @Test
    fun `duplicateDropped records the message and does not receipt it`() = runBlocking {
        val effects = RecordingEffects()
        val processor = MessageProcessor(FakeGateway(), effects, sessionManager, timerBridge, cryptoManager, held, mock())

        val outcome = processor.route(
            listOf(CfeAction.DuplicateDropped("m1"), CfeAction.ScheduleTimer("cooldown", 5_000uL)),
            incoming(),
        )

        assertEquals(ProcessingOutcome.Acked, outcome)
        assertTrue(effects.calls.contains("markProcessed:m1"))
        assertTrue(effects.calls.none { it.startsWith("receipt:") })
        assertTrue(effects.calls.none { it.startsWith("onDecrypted:") })
        org.mockito.kotlin.verify(timerBridge).schedule("cooldown", 5_000uL)
    }

    @Test
    fun `a malformed message is recorded and passed, with no delivered receipt`() = runBlocking {
        val effects = RecordingEffects()
        val processor = MessageProcessor(FakeGateway(), effects, sessionManager, timerBridge, cryptoManager, held, mock())

        val outcome = processor.route(
            // The core's order: the verdict, then the NotifyError that keeps the reason for the log.
            listOf(CfeAction.MalformedDropped("m1"), CfeAction.NotifyError("PARSE", "bad suite")),
            incoming(),
        )

        assertEquals(ProcessingOutcome.Acked, outcome)
        assertTrue(effects.calls.contains("markProcessed:m1"))
        assertTrue("garbage is not delivered", effects.calls.none { it.startsWith("receipt:") })
    }

    @Test
    fun `an empty ack follow-up replaces the check instead of keeping it`() = runBlocking {
        val effects = RecordingEffects().apply { ackedInDb = true }
        whenever(sessionManager.resolveDeviceId("alice")).thenReturn("11111111111111111111111111111111")
        val gateway = FakeGateway(
            mutableListOf(
                listOf(CfeAction.CheckAckInDb("m1")),
                emptyList(),
            ),
        )
        val processor = MessageProcessor(gateway, effects, sessionManager, timerBridge, cryptoManager, held, mock())

        val outcome = processor.process(incoming())

        assertEquals(ProcessingOutcome.Acked, outcome)
        assertEquals(2, gateway.events.size)
        assertTrue(gateway.events[1] is CfeIncomingEvent.AckDbResult)
        assertTrue(effects.calls.none { it.startsWith("onDecrypted:") })
    }

    /** Nothing held reads it: the core built a decryption error to its writer. It is sent, the
     * message recorded, and the cursor moves on — no delivered receipt, it was not delivered.
     * Mutation that reddens it: drop the `SendDecryptionError` arm (the fallthrough receipts it). */
    @Test
    fun `an unread message sends the core's decryption error and is acked`() = runBlocking {
        val effects = RecordingEffects()
        val processor = MessageProcessor(FakeGateway(), effects, sessionManager, timerBridge, cryptoManager, held, mock())

        val outcome = processor.route(
            listOf(
                CfeAction.PersistAck("m1", 1uL),
                CfeAction.SendDecryptionError("bob", "m1", byteArrayOf(9), enveloped = false),
                CfeAction.NotifyError("decrypt_failed", "AEAD decryption failed"),
            ),
            incoming(),
        )

        assertEquals(ProcessingOutcome.Acked, outcome)
        assertTrue(effects.calls.contains("decryptionError:bob:m1"))
        assertTrue(effects.calls.contains("markProcessed:m1"))
        assertTrue(effects.calls.none { it.startsWith("receipt:") })
    }

    /** The same verdict with no error to send (an unsealed message): recorded, acked, and — the
     * point — not receipted as delivered. Mutation that reddens it: drop the `decrypt_failed` arm. */
    @Test
    fun `an unread message with no error to send is acked without a receipt`() = runBlocking {
        val effects = RecordingEffects()
        val processor = MessageProcessor(FakeGateway(), effects, sessionManager, timerBridge, cryptoManager, held, mock())

        val outcome = processor.route(
            listOf(CfeAction.NotifyError("decrypt_failed", "AEAD decryption failed")),
            incoming(),
        )

        assertEquals(ProcessingOutcome.Acked, outcome)
        assertTrue(effects.calls.contains("markProcessed:m1"))
        assertTrue(effects.calls.none { it.startsWith("receipt:") })
    }

    /** A handshake header the held session (if any) cannot read: the core queued the message and
     * asks for an open, which fetches nothing and announces nothing. Mutation that reddens it: return Deferred without opening, or skip holding
     * the envelope (the drain could not route it). */
    @Test
    fun `openReceiving opens the named device and holds the envelope`() = runBlocking {
        val effects = RecordingEffects()
        val processor = MessageProcessor(FakeGateway(), effects, sessionManager, timerBridge, cryptoManager, held, mock())

        val outcome = processor.route(listOf(CfeAction.OpenReceiving(device)), incoming())

        assertEquals(ProcessingOutcome.Processed, outcome)
        assertTrue(effects.calls.contains("open:$device"))
        assertEquals("m1", held.take("m1")?.messageId)
    }

    @Test
    fun `no actionable decision acks as delivered`() = runBlocking {
        val effects = RecordingEffects()
        val processor = MessageProcessor(FakeGateway(), effects, sessionManager, timerBridge, cryptoManager, held, mock())

        val outcome = processor.route(listOf(CfeAction.PruneAckStore(0uL)), incoming())

        assertEquals(ProcessingOutcome.Acked, outcome)
        assertTrue(effects.calls.contains("receipt:m1:delivered"))
    }

    // ── process() end-to-end (with fake gateway) ─────────────────────────

    @Test
    fun `process drives handleEvent and routes decrypted`() = runBlocking {
        val effects = RecordingEffects()
        whenever(sessionManager.resolveDeviceId("alice")).thenReturn("11111111111111111111111111111111")
        val gateway = FakeGateway(mutableListOf(listOf(CfeAction.MessageDecrypted("alice", "m1", byteArrayOf(9)))))
        val processor = MessageProcessor(gateway, effects, sessionManager, timerBridge, cryptoManager, held, mock())

        val outcome = processor.process(incoming())

        assertEquals(ProcessingOutcome.Processed, outcome)
        assertTrue(gateway.events.first() is CfeIncomingEvent.MessageReceived)
        assertTrue(effects.calls.contains("onDecrypted:alice:m1"))
    }

    /** A message out of a session envelope names its writer by the session, not a certificate: the
     * device comes from the matched pair and the session goes to the core, so an unreadable one
     * is answered along that pair. Mutation that reddens it: drop `envelopeSession` from the
     * event, or resolve the device from the account. */
    @Test
    fun `an enveloped message reaches the core from its pair's device, naming its session`() = runBlocking<Unit> {
        val gateway = FakeGateway(mutableListOf(listOf(CfeAction.MessageDecrypted("d", "m1", byteArrayOf(9)))))
        val processor = MessageProcessor(gateway, RecordingEffects(), sessionManager, timerBridge, cryptoManager, held, mock())

        processor.process(incoming().copy(viaSealedSender = true, envelopeSession = "5e55", envelopeDevice = "22222222222222222222222222222222"))

        val event = gateway.events.first() as CfeIncomingEvent.MessageReceived
        assertEquals("22222222222222222222222222222222", event.from)
        assertEquals("5e55", event.envelopeSession)
        assertNull(event.senderCertificate)
        verify(sessionManager, never()).resolveDeviceId(any())
    }

    /** The core's answer along the pair is sent as it says — an envelope. */
    @Test
    fun `a decryption error the core sealed into an envelope is sent as one`() = runBlocking {
        val effects = RecordingEffects()
        val processor = MessageProcessor(FakeGateway(), effects, sessionManager, timerBridge, cryptoManager, held, mock())

        processor.route(listOf(CfeAction.SendDecryptionError("bob", "m1", byteArrayOf(9), enveloped = true)), incoming())

        assertTrue(effects.calls.contains("decryptionError:bob:m1:enveloped"))
    }

    @Test
    fun `process handles checkAckInDb round-trip`() = runBlocking {
        val effects = RecordingEffects().apply { ackedInDb = false }
        whenever(sessionManager.resolveDeviceId("alice")).thenReturn("11111111111111111111111111111111")
        val gateway = FakeGateway(
            mutableListOf(
                listOf(CfeAction.CheckAckInDb("m1")),                       // first pass: cache miss
                listOf(CfeAction.MessageDecrypted("alice", "m1", byteArrayOf(1))), // after AckDbResult
            ),
        )
        val processor = MessageProcessor(gateway, effects, sessionManager, timerBridge, cryptoManager, held, mock())

        val outcome = processor.process(incoming())

        assertEquals(ProcessingOutcome.Processed, outcome)
        assertEquals(2, gateway.events.size)
        assertTrue(gateway.events[1] is CfeIncomingEvent.AckDbResult)
        assertTrue(effects.calls.contains("onDecrypted:alice:m1"))
    }

    @Test
    fun `process on handleEvent throw records the message and tells nobody`() = runBlocking {
        val effects = RecordingEffects()
        whenever(sessionManager.resolveDeviceId("alice")).thenReturn("11111111111111111111111111111111")
        val gateway = object : OrchestratorGateway {
            override fun handleEvent(event: CfeIncomingEvent): List<CfeAction> = throw RuntimeException("boom")
        }
        val processor = MessageProcessor(gateway, effects, sessionManager, timerBridge, cryptoManager, held, mock())

        val outcome = processor.process(incoming())

        assertEquals(ProcessingOutcome.Acked, outcome)
        assertTrue(effects.calls.contains("markProcessed:m1"))
        // A throw is the core refusing the event, not a session failing to read it: there is no
        // state to name in a decryption error. It used to send END_SESSION to the account.
        assertTrue(effects.calls.none { it.startsWith("decryptionError:") })
    }

    @Test
    fun `process acks malformed payload reported by the core as NotifyError`() = runBlocking {
        // Wire parsing lives in Rust: a malformed blob comes back as NotifyError,
        // which carries no routing decision — the processor ACKs it as delivered
        // so the cursor advances and the message is never re-fetched.
        val effects = RecordingEffects()
        whenever(sessionManager.resolveDeviceId("alice")).thenReturn("11111111111111111111111111111111")
        val gateway = FakeGateway(
            mutableListOf(listOf(CfeAction.NotifyError("MALFORMED_WIRE_PAYLOAD", "too short"))),
        )
        val processor = MessageProcessor(gateway, effects, sessionManager, timerBridge, cryptoManager, held, mock())

        val outcome = processor.process(incoming().copy(encryptedPayload = ByteArray(4)))

        assertEquals(ProcessingOutcome.Acked, outcome)
        assertTrue(effects.calls.contains("receipt:m1:delivered"))
    }

    // ── SESSION_RESET_INIT is a message like any other ─────────────────

    private val device = "11111111111111111111111111111111"

    /** Nothing sends SESSION_RESET_INIT since 2026-09-27: a session opens from any message with a
     * handshake header (`decisions/sessions-renew-by-sending.md`). One from an older client goes
     * to the core as a message, which opens a new state beside the one held.
     * Mutation that reddens it: bring back a branch that takes content type 24 aside. */
    @Test
    fun `a reset init goes to the core as a message`() = runBlocking {
        whenever(sessionManager.resolveDeviceId("alice")).thenReturn(device)
        val gateway = FakeGateway()
        MessageProcessor(gateway, RecordingEffects(), sessionManager, timerBridge, cryptoManager, held, mock())
            .process(incoming().copy(contentType = ContentType.CONTENT_TYPE_SESSION_RESET_INIT))

        val event = gateway.events.filterIsInstance<CfeIncomingEvent.MessageReceived>().single()
        assertEquals(device, event.from)
    }

    // ── The device comes from the certificate, never from a guess ────────

    private val senderDevice = "22222222222222222222222222222222"

    private fun certificate(deviceId: String = senderDevice) = SenderCertificate(
        userId = "alice",
        domain = "konstruct.cc",
        identityKey = ByteArray(32) { 5 },
        deviceId = deviceId,
        issuedAt = 1_000L,
        expiresAt = 2_000L,
        signature = ByteArray(64),
    )

    /** The certificate names the ratchet, and it travels to the core with the message — the only
     * thing a first message opens from. Mutation that reddens it: take the pinned device first,
     * or drop the certificate from the event. */
    @Test
    fun `the certificate names the device and goes to the core with the message`() = runBlocking {
        whenever(sessionManager.resolveDeviceId("alice")).thenReturn(device)
        val gateway = FakeGateway()
        val cert = certificate()
        MessageProcessor(gateway, RecordingEffects(), sessionManager, timerBridge, cryptoManager, held, mock())
            .process(incoming().copy(senderCertificate = cert))

        val event = gateway.events.filterIsInstance<CfeIncomingEvent.MessageReceived>().single()
        assertEquals(senderDevice, event.from)
        assertEquals(cert, event.senderCertificate)
    }

    /** No certificate and no session with the account: nothing names the device, and the message
     * is refused rather than filed under a guess. Mutation that reddens it: defer it, or ask the
     * directory for a device. */
    @Test
    fun `a message nothing names a device for is refused`() = runBlocking {
        whenever(sessionManager.resolveDeviceId("alice")).thenReturn(null)
        val gateway = FakeGateway()
        val effects = RecordingEffects()

        val outcome = MessageProcessor(gateway, effects, sessionManager, timerBridge, cryptoManager, held, mock())
            .process(incoming())

        assertEquals(ProcessingOutcome.Acked, outcome)
        assertTrue(effects.calls.contains("markProcessed:m1"))
        assertTrue(gateway.events.isEmpty())
    }

    /** A per-device copy for another device is recognised from the certificate's key alone —
     * no bundle fetch, which would tell the server whom the sealed message was from. Mutation
     * that reddens it: treat every copy as ours. */
    @Test
    fun `a copy for another device is acked from the certificate key`() = runBlocking {
        whenever(cryptoManager.currentDeviceId()).thenReturn(device)
        whenever(
            cryptoManager.deviceCopyTagMatches(
                org.mockito.kotlin.any(),
                org.mockito.kotlin.any(),
                org.mockito.kotlin.any(),
            ),
        ).thenReturn(false)
        val gateway = FakeGateway()
        val effects = RecordingEffects()
        val copy = incoming(id = "base-1-fd-0123456789abcdef").copy(senderCertificate = certificate())

        val outcome = MessageProcessor(gateway, effects, sessionManager, timerBridge, cryptoManager, held, mock())
            .process(copy)

        assertEquals(ProcessingOutcome.Acked, outcome)
        assertTrue(gateway.events.isEmpty())
        org.mockito.kotlin.verifyNoInteractions(sessionManager)
    }

    // ── What the core holds is answered by id ───────────────────────────

    /** A SENDER_SYNC that waited in the core's queue comes back as a bare `MessageDecrypted`;
     * the kept envelope is what makes it our own copy rather than an incoming bubble. Mutation
     * that reddens it: route drained messages with `onDecrypted` unconditionally. */
    @Test
    fun `a drained sender sync is routed as our own copy`() = runBlocking {
        val effects = RecordingEffects()
        held.hold(incoming(id = "sync-1").copy(contentType = ContentType.CONTENT_TYPE_SENDER_SYNC))
        val bridge = CfeTimerBridge(FakeGateway(), effects, held, sessionManager)

        bridge.execute(listOf(CfeAction.MessageDecrypted(senderDevice, "sync-1", byteArrayOf(1))))

        assertTrue(effects.calls.contains("onSenderSync:$senderDevice:sync-1"))
        assertTrue(effects.calls.none { it.startsWith("onDecrypted:") })
    }

    /** An init opened by the core comes back as an ordinary decrypt; its plaintext is a nonce,
     * not a message. Mutation that reddens it: drop the SESSION_RESET_INIT arm of
     * `deliverDecrypted` — the nonce becomes a `$<uuid>` bubble, as it did on the stand. */
    @Test
    fun `an opened reset init never reaches the transcript`() = runBlocking {
        val effects = RecordingEffects()
        held.hold(incoming(id = "sri-1").copy(contentType = ContentType.CONTENT_TYPE_SESSION_RESET_INIT))
        val bridge = CfeTimerBridge(FakeGateway(), effects, held, sessionManager)

        bridge.execute(listOf(CfeAction.MessageDecrypted(senderDevice, "sri-1", "\$CEABF9BC".toByteArray())))

        assertTrue(effects.calls.contains("markProcessed:sri-1"))
        assertTrue(effects.calls.none { it.startsWith("onDecrypted:") || it.startsWith("onSenderSync:") })
    }

    // ── The core asks for an open: OpenSession ─────────────────────────

    /** The PQXDH v2 upgrade sweep asks for a new session over a held one. The bridge answers
     * with that device's bundle as `SessionBundleFetched` and carries out what the core answers
     * — the save of the record included. Until 2026-09-28 it called `reopen_session`, whose
     * answer is an id: the record went unsaved and nothing drained. Mutations that redden it:
     * restore the log-only branch; drop the `execute(answer)`. */
    @Test
    fun `an OpenSession is answered with the device's bundle and the core's answer is carried out`() = runBlocking {
        val bundle = shared.proto.services.v1.KeyServiceOuterClass.PreKeyBundle.getDefaultInstance()
            .toBinaryKeyBundle(ByteArray(0))
        org.mockito.kotlin.wheneverBlocking { sessionManager.bundleForOpenSession(senderDevice) }.thenReturn(bundle)
        val gateway = FakeGateway(mutableListOf(listOf(CfeAction.DuplicateDropped("drained"))))
        val effects = RecordingEffects()
        val bridge = CfeTimerBridge(gateway, effects, held, sessionManager)

        bridge.execute(listOf(CfeAction.OpenSession(senderDevice)))

        val sent = gateway.events.single() as CfeIncomingEvent.SessionBundleFetched
        assertEquals(senderDevice, sent.contactId)
        assertEquals(bundle, sent.bundle)
        assertTrue(effects.calls.contains("markProcessed:drained"))
    }

    /** No bundle to hand over (a failed fetch, a device the registry does not know) is said as
     * such, so the core ends the open now rather than at its 30 s time-out, and the actions
     * after it still run. */
    @Test
    fun `an OpenSession with no bundle is answered as unavailable`() = runBlocking {
        org.mockito.kotlin.wheneverBlocking { sessionManager.bundleForOpenSession(senderDevice) }
            .thenThrow(IllegalStateException("no account mapping"))
        val gateway = FakeGateway()
        val effects = RecordingEffects()
        val bridge = CfeTimerBridge(gateway, effects, held, sessionManager)

        bridge.execute(listOf(
            CfeAction.OpenSession(senderDevice),
            CfeAction.DuplicateDropped("after-open"),
        ))

        assertEquals(listOf<CfeIncomingEvent>(CfeIncomingEvent.SessionBundleUnavailable(senderDevice)), gateway.events)
        assertTrue(effects.calls.contains("markProcessed:after-open"))
    }
}
