package com.construct.messenger.service

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import shared.proto.core.v1.EnvelopeOuterClass.ContentType
import uniffi.construct_core.CfeAction
import uniffi.construct_core.CfeIncomingEvent

class MessageProcessorTest {

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
        override suspend fun persistMessage(messageJson: String) { calls += "persist:$messageJson" }
        override suspend fun sendReceipt(messageId: String, toUserId: String, status: String) { calls += "receipt:$messageId:$status" }
        override suspend fun notifyNewMessage(chatId: String, preview: String) { calls += "notify:$chatId" }
        override suspend fun markDelivered(messageId: String) { calls += "markDelivered:$messageId" }
        override suspend fun markProcessed(messageId: String, senderId: String) { calls += "markProcessed:$messageId" }
        override suspend fun saveSession(key: String, data: ByteArray) { calls += "saveSession:$key" }
        override suspend fun archiveSession(contactId: String) { calls += "archive:$contactId" }
        override suspend fun requestHeal(contactId: String, role: String) { calls += "heal:$contactId:$role" }
        override suspend fun requestEndSession(contactId: String) { calls += "endSession:$contactId" }
        override suspend fun requestKeyBundle(userId: String, incoming: MessageRouter.IncomingMessage) { calls += "keyBundle:$userId" }
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
        val processor = MessageProcessor(FakeGateway(), effects)
        val actions = listOf(
            CfeAction.MessageDecrypted("alice", "m1", byteArrayOf(7)),
            CfeAction.PersistMessage("{json}"),
            CfeAction.SendReceipt("m1", "delivered"),
            CfeAction.NotifyNewMessage("chat1", "hi"),
        )

        val outcome = processor.route(actions, incoming())

        assertEquals(ProcessingOutcome.Processed, outcome)
        assertTrue(effects.calls.contains("onDecrypted:alice:m1"))
        assertTrue(effects.calls.contains("persist:{json}"))
        assertTrue(effects.calls.contains("receipt:m1:delivered"))
        assertTrue(effects.calls.contains("notify:chat1"))
    }

    @Test
    fun `sessionHealNeeded defers and requests heal`() = runBlocking {
        val effects = RecordingEffects()
        val processor = MessageProcessor(FakeGateway(), effects)

        val outcome = processor.route(listOf(CfeAction.SessionHealNeeded("bob", "initiator")), incoming())

        assertEquals(ProcessingOutcome.Deferred, outcome)
        assertTrue(effects.calls.contains("heal:bob:initiator"))
    }

    @Test
    fun `sendEndSession acks, marks processed and requests end session`() = runBlocking {
        val effects = RecordingEffects()
        val processor = MessageProcessor(FakeGateway(), effects)

        val outcome = processor.route(listOf(CfeAction.SendEndSession("bob")), incoming())

        assertEquals(ProcessingOutcome.Acked, outcome)
        assertTrue(effects.calls.contains("receipt:m1:failed"))
        assertTrue(effects.calls.contains("markProcessed:m1"))
        assertTrue(effects.calls.contains("endSession:bob"))
    }

    @Test
    fun `fetchPublicKeyBundle defers and requests bundle`() = runBlocking {
        val effects = RecordingEffects()
        val processor = MessageProcessor(FakeGateway(), effects)

        val outcome = processor.route(listOf(CfeAction.FetchPublicKeyBundle("carol")), incoming())

        assertEquals(ProcessingOutcome.Deferred, outcome)
        assertTrue(effects.calls.contains("keyBundle:carol"))
    }

    @Test
    fun `no actionable decision acks as delivered`() = runBlocking {
        val effects = RecordingEffects()
        val processor = MessageProcessor(FakeGateway(), effects)

        val outcome = processor.route(listOf(CfeAction.PruneAckStore(0uL)), incoming())

        assertEquals(ProcessingOutcome.Acked, outcome)
        assertTrue(effects.calls.contains("receipt:m1:delivered"))
    }

    // ── process() end-to-end (with fake gateway) ─────────────────────────

    @Test
    fun `process drives handleEvent and routes decrypted`() = runBlocking {
        val effects = RecordingEffects()
        val gateway = FakeGateway(mutableListOf(listOf(CfeAction.MessageDecrypted("alice", "m1", byteArrayOf(9)))))
        val processor = MessageProcessor(gateway, effects)

        val outcome = processor.process(incoming())

        assertEquals(ProcessingOutcome.Processed, outcome)
        assertTrue(gateway.events.first() is CfeIncomingEvent.MessageReceived)
        assertTrue(effects.calls.contains("onDecrypted:alice:m1"))
    }

    @Test
    fun `process handles checkAckInDb round-trip`() = runBlocking {
        val effects = RecordingEffects().apply { ackedInDb = false }
        val gateway = FakeGateway(
            mutableListOf(
                listOf(CfeAction.CheckAckInDb("m1")),                       // first pass: cache miss
                listOf(CfeAction.MessageDecrypted("alice", "m1", byteArrayOf(1))), // after AckDbResult
            ),
        )
        val processor = MessageProcessor(gateway, effects)

        val outcome = processor.process(incoming())

        assertEquals(ProcessingOutcome.Processed, outcome)
        assertEquals(2, gateway.events.size)
        assertTrue(gateway.events[1] is CfeIncomingEvent.AckDbResult)
        assertTrue(effects.calls.contains("onDecrypted:alice:m1"))
    }

    @Test
    fun `process on handleEvent throw ends session and acks`() = runBlocking {
        val effects = RecordingEffects()
        val gateway = object : OrchestratorGateway {
            override fun handleEvent(event: CfeIncomingEvent): List<CfeAction> = throw RuntimeException("boom")
        }
        val processor = MessageProcessor(gateway, effects)

        val outcome = processor.process(incoming())

        assertEquals(ProcessingOutcome.Acked, outcome)
        assertTrue(effects.calls.contains("markProcessed:m1"))
        assertTrue(effects.calls.contains("endSession:alice"))
    }

    @Test
    fun `process acks malformed payload reported by the core as NotifyError`() = runBlocking {
        // Wire parsing lives in Rust: a malformed blob comes back as NotifyError,
        // which carries no routing decision — the processor ACKs it as delivered
        // so the cursor advances and the message is never re-fetched.
        val effects = RecordingEffects()
        val gateway = FakeGateway(
            mutableListOf(listOf(CfeAction.NotifyError("MALFORMED_WIRE_PAYLOAD", "too short"))),
        )
        val processor = MessageProcessor(gateway, effects)

        val outcome = processor.process(incoming().copy(encryptedPayload = ByteArray(4)))

        assertEquals(ProcessingOutcome.Acked, outcome)
        assertTrue(effects.calls.contains("receipt:m1:delivered"))
    }
}
