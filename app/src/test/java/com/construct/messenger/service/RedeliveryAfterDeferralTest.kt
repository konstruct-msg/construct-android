package com.construct.messenger.service

import com.construct.messenger.data.api.StreamCursorStore
import com.construct.messenger.data.api.StreamCursorTracker
import com.construct.messenger.data.local.AckStore
import com.google.protobuf.ByteString
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.mock
import shared.proto.core.v1.EnvelopeOuterClass.ContentType
import shared.proto.core.v1.EnvelopeOuterClass.Envelope
import shared.proto.core.v1.Identity.UserId

/**
 * Which copies of a message the router lets through to the processor (vault todo 282, decision
 * `one-answer-to-already-processed`). Until 2026-11-08 every id seen stayed in a window, so the
 * redelivery a deferred message waited for was dropped unreported and the cursor stood on it until
 * the process died.
 */
class RedeliveryAfterDeferralTest {
    private val processed = mutableSetOf<String>()
    private val ackStore = object : AckStore {
        override suspend fun hydrate() = Unit
        override fun isProcessed(messageId: String) = messageId in processed
        override suspend fun markProcessed(messageId: String, senderId: String) { processed += messageId }
        override suspend fun prune(olderThanMs: Long) = 0
    }
    private val tracker = StreamCursorTracker(mock<StreamCursorStore>(), ackStore)
    private val held = HeldEnvelopes()
    private val router = MessageRouter(
        mock(), mock(), ServerOrderBook(), tracker, mock(), mock(), mock(), held, ackStore,
    )
    private val routed = mutableListOf<String>()
    private val collector = kotlinx.coroutines.GlobalScope.launch(Dispatchers.Unconfined, CoroutineStart.UNDISPATCHED) {
        router.routed.collect { event ->
            (event as? MessageRouter.RoutedEvent.Incoming)?.let { routed += it.message.messageId }
        }
    }

    @After
    fun stop() = collector.cancel()

    private fun envelope(id: String) = Envelope.newBuilder()
        .setMessageId(id)
        .setSender(UserId.newBuilder().setUserId("alice"))
        .setContentType(ContentType.CONTENT_TYPE_E2EE_SIGNAL)
        .setEncryptedPayload(ByteString.copyFrom(byteArrayOf(1)))
        .build()

    /** Mutation that reddens it: leave the id in flight in `settle`. */
    @Test
    fun `a message deferred for a redelivery gets it`() = runBlocking<Unit> {
        router.ingest(envelope("m1"), null)
        router.settle("m1", StreamCursorTracker.Outcome.Deferred)
        router.ingest(envelope("m1"), null)
        assertEquals(listOf("m1", "m1"), routed)
    }

    /** Mutation that reddens it: leave the id in flight in `abandon`. */
    @Test
    fun `a message whose processing threw gets its redelivery`() = runBlocking<Unit> {
        router.ingest(envelope("m1"), null)
        router.abandon("m1")
        router.ingest(envelope("m1"), null)
        assertEquals(listOf("m1", "m1"), routed)
    }

    /** The stream and the pending drain can carry one message at once. Mutation: drop the in-flight check. */
    @Test
    fun `a second copy while the first is in flight goes no further`() = runBlocking<Unit> {
        router.ingest(envelope("m1"), null)
        router.ingest(envelope("m1"), null)
        assertEquals(listOf("m1"), routed)
    }

    /**
     * The core holds it behind an open; asking again would get `DuplicateDropped`, which marks it
     * processed before it is stored. Mutation: drop the held check.
     */
    @Test
    fun `a copy of one the core holds waits for the drain`() = runBlocking<Unit> {
        router.ingest(envelope("m1"), null)
        held.hold(MessageRouter.IncomingMessage("m1", "alice", ContentType.CONTENT_TYPE_E2EE_SIGNAL, byteArrayOf(1), 0, false))
        router.settle("m1", StreamCursorTracker.Outcome.Deferred)
        router.ingest(envelope("m1"), null)
        assertEquals(listOf("m1"), routed)
    }

    /** Mutations: route a processed one again; drop it without reporting — the cursor stays. */
    @Test
    fun `a processed one is passed and lets the cursor on`() = runBlocking<Unit> {
        processed += "m1"
        tracker.track("m1", "c1")
        router.ingest(envelope("m1"), null)
        assertEquals(emptyList<String>(), routed)
        assertEquals("c1", tracker.committedCursor())
    }
}
