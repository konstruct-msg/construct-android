package com.construct.messenger.service

import com.construct.messenger.data.local.FakeSessionBlobStore
import com.construct.messenger.domain.usecase.ResendOutcome
import com.construct.messenger.domain.usecase.SendMessageUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * TODO 155. A copy one of the recipient's devices refused is sent again — to that device only, on
 * a schedule of its own, within five attempts and a day. **Canon:** iOS `FanoutRetryQueueTests`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FanoutRetriesTest {

    private val store = FakeSessionBlobStore()
    private val send: SendMessageUseCase = mock()

    private fun TestScope.queue() = FanoutRetryQueue(store) { testScheduler.currentTime }

    private fun TestScope.retries(queue: FanoutRetryQueue = queue()) =
        FanoutRetries(queue, { send }, backgroundScope) { testScheduler.currentTime }

    private suspend fun ready() {
        whenever(send.isReady()).thenReturn(true)
        whenever(send.resend(any(), any(), any())).thenReturn(ResendOutcome.SENT)
    }

    /** Mutation: retry every device the entry ever named, or the recipient's whole set — this reddens. */
    @Test
    fun `only the owed device gets the copy again, and only once it took it`() = runTest {
        ready()
        val r = retries()
        r.owed("m-1", "peer", "me", listOf("dev-2"))

        advanceTimeBy(FanoutRetryQueue.BASE_BACKOFF_MS + 2_000)
        runCurrent()

        verify(send).resend("peer", "dev-2", "m-1")
        verify(send, never()).resend(any(), eq("dev-1"), any())
        assertTrue(queue().all().isEmpty())
    }

    /** #236: no network change needed — the pass comes at the entry's own due time. */
    @Test
    fun `the pass comes when the entry is due, not before`() = runTest {
        ready()
        val r = retries()
        r.owed("m-1", "peer", "me", listOf("dev-2"))

        advanceTimeBy(FanoutRetryQueue.BASE_BACKOFF_MS - 1_000)
        runCurrent()
        verify(send, never()).resend(any(), any(), any())

        advanceTimeBy(3_000)
        runCurrent()
        verify(send).resend("peer", "dev-2", "m-1")
    }

    /** Mutation: union the devices lost into the entry instead of replacing — the reached one is sent twice. */
    @Test
    fun `a pass that reaches one device and loses another owes only the lost one`() = runTest {
        ready()
        whenever(send.resend("peer", "dev-2", "m-1")).thenReturn(ResendOutcome.FAILED)
        val q = queue()
        val r = retries(q)
        r.owed("m-1", "peer", "me", listOf("dev-2", "dev-3"))

        advanceTimeBy(FanoutRetryQueue.BASE_BACKOFF_MS + 2_000)
        runCurrent()

        val entry = q.all().single()
        assertEquals(listOf("dev-2"), entry.owedDeviceIds)
        assertEquals(1, entry.attempts)
    }

    /** The original send is the first attempt; four retries follow, then the entry goes unsent. */
    @Test
    fun `after five attempts the entry is dropped`() = runTest {
        ready()
        whenever(send.resend(any(), any(), any())).thenReturn(ResendOutcome.FAILED)
        val q = queue()
        val r = retries(q)
        r.owed("m-1", "peer", "me", listOf("dev-2"))

        advanceTimeBy(FanoutRetryQueue.ENTRY_TTL_MS - 60_000)
        runCurrent()

        verify(send, times(FanoutRetryQueue.MAX_ATTEMPTS - 1)).resend("peer", "dev-2", "m-1")
        assertTrue(q.all().isEmpty())
    }

    @Test
    fun `the wait doubles from thirty seconds`() = runTest {
        val q = queue()
        q.enqueue("m-1", "peer", "me", listOf("dev-2"))
        assertEquals(30_000L, q.all().single().nextAttemptAtMs)

        val after = q.recordAttempt("m-1|peer")!!
        assertEquals(60_000L, after.nextAttemptAtMs - testScheduler.currentTime)
        val again = q.recordAttempt("m-1|peer")!!
        assertEquals(120_000L, again.nextAttemptAtMs - testScheduler.currentTime)
    }

    /** Mutation: reset `createdAt` on a second enqueue — the entry outlives its day. */
    @Test
    fun `failing again does not extend the day, and an expired entry is dropped on read`() = runTest {
        val q = queue()
        q.enqueue("m-1", "peer", "me", listOf("dev-2"))
        advanceTimeBy(FanoutRetryQueue.ENTRY_TTL_MS - 1_000)
        q.enqueue("m-1", "peer", "me", listOf("dev-3"))
        assertEquals(listOf("dev-2", "dev-3"), q.all().single().owedDeviceIds)
        assertEquals(0L, q.all().single().createdAtMs)

        advanceTimeBy(2_000)
        assertTrue(q.due().isEmpty())
        assertNull(q.nextDue())
        assertTrue(q.all().isEmpty())
    }

    @Test
    fun `owed copies survive a restart`() = runTest {
        queue().enqueue("m-1", "peer", "me", listOf("dev-2"))

        val reopened = FanoutRetryQueue(store) { testScheduler.currentTime }

        assertEquals(listOf("dev-2"), reopened.all().single().owedDeviceIds)
        assertNotNull(store.rows[FanoutRetryQueue.STORAGE_KEY])
    }

    /** Before the core is up nothing can go; the attempt is not spent, the pass comes again. */
    @Test
    fun `a pass that cannot send yet spends nothing and comes back`() = runTest {
        whenever(send.isReady()).thenReturn(false)
        val q = queue()
        val r = retries(q)
        r.owed("m-1", "peer", "me", listOf("dev-2"))

        advanceTimeBy(FanoutRetryQueue.BASE_BACKOFF_MS + 2_000)
        runCurrent()
        assertEquals(0, q.all().single().attempts)

        ready()
        advanceTimeBy(FanoutRetryQueue.BASE_BACKOFF_MS + 2_000)
        runCurrent()
        verify(send).resend("peer", "dev-2", "m-1")
        assertTrue(q.all().isEmpty())
    }

    @Test
    fun `a message no longer ours to send is dropped`() = runTest {
        ready()
        whenever(send.resend(any(), any(), any())).thenReturn(ResendOutcome.NOT_OURS)
        val q = queue()
        retries(q).owed("m-1", "peer", "me", listOf("dev-2", "dev-3"))

        advanceTimeBy(FanoutRetryQueue.BASE_BACKOFF_MS + 2_000)
        runCurrent()

        assertTrue(q.all().isEmpty())
        verify(send, times(1)).resend(any(), any(), any())
    }

    @Test
    fun `a message every device took clears what an earlier attempt left`() = runTest {
        val q = queue()
        val r = retries(q)
        r.owed("m-1", "peer", "me", listOf("dev-2"))

        r.settled("m-1", "peer")

        assertTrue(q.all().isEmpty())
        assertNull(store.rows[FanoutRetryQueue.STORAGE_KEY])
    }

    /** Identifiers only: no text, no media — the copy is rebuilt from the row. */
    @Test
    fun `what is stored is ids and times`() = runTest {
        queue().enqueue("m-1", "peer", "me", listOf("dev-2"))
        val stored = String(store.rows.getValue(FanoutRetryQueue.STORAGE_KEY))
        assertEquals(
            setOf("baseMessageId", "recipientUserId", "senderUserId", "owedDeviceIds", "attempts", "nextAttemptAtMs", "createdAtMs"),
            org.json.JSONArray(stored).getJSONObject(0).keys().asSequence().toSet(),
        )
    }
}
