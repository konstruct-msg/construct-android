package com.construct.messenger.data.api

import com.construct.messenger.data.local.AckStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class StreamCursorTrackerTest {

    private val saved = mutableListOf<String>()
    private val processed = mutableSetOf<String>()
    private val store: StreamCursorStore = mock()
    private val ackStore = object : AckStore {
        override suspend fun hydrate() = Unit
        override fun isProcessed(messageId: String) = messageId in processed
        override suspend fun markProcessed(messageId: String, senderId: String) {
            processed += messageId
        }
        override suspend fun prune(olderThanMs: Long) = 0
    }
    private lateinit var tracker: StreamCursorTracker

    @Before
    fun setUp() {
        whenever(store.save(any())).then { saved += it.getArgument<String>(0); Unit }
        tracker = StreamCursorTracker(store, ackStore)
    }

    @Test
    fun `durable entries advance the cursor in arrival order`() {
        tracker.track("m1", "c1")
        tracker.track("m2", "c2")

        tracker.resolve("m1")
        tracker.resolve("m2")

        assertEquals(listOf("c1", "c2"), saved)
        assertEquals("c2", tracker.committedCursor())
    }

    @Test
    fun `a deferred head holds every later entry until it is resolved`() {
        tracker.track("m1", "c1")
        tracker.track("m2", "c2")
        tracker.track("m3", "c3")

        tracker.report("m1", StreamCursorTracker.Outcome.Deferred)
        tracker.resolve("m2")
        tracker.resolve("m3")
        assertEquals(emptyList<String>(), saved)

        tracker.resolve("m1")
        assertEquals(listOf("c3"), saved)
    }

    @Test
    fun `a message the ack store already holds counts as resolved`() {
        // The router drops a stream copy of a message the pending drain already handled; nothing
        // reports it, so the ack store is what keeps it from stalling the cursor.
        processed += "m1"
        tracker.track("m1", "c1")
        tracker.track("m2", "c2")

        tracker.resolve("m2")

        assertEquals(listOf("c2"), saved)
    }

    @Test
    fun `a deferred message the core drains later releases the cursor on the next report`() {
        tracker.track("m1", "c1")
        tracker.report("m1", StreamCursorTracker.Outcome.Deferred)
        tracker.track("m2", "c2")

        processed += "m1" // decrypted by SessionInitCompleted, marked processed by the executor
        tracker.resolve("m2")

        assertEquals(listOf("c2"), saved)
    }

    @Test
    fun `a receipt cursor cannot leapfrog a held message`() {
        tracker.track("m1", "c1")
        tracker.report("m1", StreamCursorTracker.Outcome.Deferred)

        tracker.trackResolved("r1")

        verify(store, never()).save(any())
    }

    @Test
    fun `skip leaves a pending entry pending`() {
        tracker.track("m1", "c1")

        tracker.report("m1", StreamCursorTracker.Outcome.Skip)

        verify(store, never()).save(any())
        assertEquals("m1… Pending", tracker.headBlocker())
    }

    @Test
    fun `reset drops tracking and the stored cursor is where a new connection resumes`() {
        whenever(store.load()).thenReturn("persisted")
        tracker.track("m1", "c1")
        tracker.resolve("m1")

        tracker.reset()

        assertEquals("persisted", tracker.committedCursor())
        assertNull(tracker.headBlocker())
    }
}
