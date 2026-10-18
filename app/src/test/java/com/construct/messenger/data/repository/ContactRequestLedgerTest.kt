package com.construct.messenger.data.repository

import com.construct.messenger.data.local.FakeSessionBlobStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The acks queue and the sent map behind TODO 157. **Canon:** iOS `AcceptedRequestAcksTests`. */
class ContactRequestLedgerTest {
    private val blobs = FakeSessionBlobStore()
    private val ledger = ContactRequestLedger(blobs)

    /** Mutation: drop the `!in` check — the id goes out twice. */
    @Test
    fun `queuing an id twice keeps one`() = runTest {
        ledger.ack("r1")
        ledger.ack("r1")
        assertEquals(listOf("r1"), ledger.pendingAcks())
    }

    /** Mutation: clear the queue in `pendingAcks` — a lost answer loses the ids. */
    @Test
    fun `ids leave only when the poll that carried them is answered`() = runTest {
        ledger.ack("r1")
        val carried = ledger.pendingAcks()
        // The poll failed: nothing confirmed, the next carries it again.
        assertEquals(listOf("r1"), ledger.pendingAcks())
        ledger.confirmAcks(carried)
        assertEquals(emptyList<String>(), ledger.pendingAcks())
    }

    /** Mutation: `confirmAcks` empties the queue — r2 is lost without ever being sent. */
    @Test
    fun `an id queued while a poll is in flight waits for the next`() = runTest {
        ledger.ack("r1")
        val carried = ledger.pendingAcks()
        ledger.ack("r2")
        ledger.confirmAcks(carried)
        assertEquals(listOf("r2"), ledger.pendingAcks())
    }

    /** Mutation: no `take` — 150 ids in one request, of which the server honours 100. */
    @Test
    fun `a poll carries at most a hundred, oldest first`() = runTest {
        repeat(150) { ledger.ack("r$it") }
        val first = ledger.pendingAcks()
        assertEquals(ContactRequestLedger.PER_POLL, first.size)
        assertEquals("r0", first.first())
        ledger.confirmAcks(first)
        assertEquals((100 until 150).map { "r$it" }, ledger.pendingAcks())
    }

    @Test
    fun `a sent request is matched to its user until it is forgotten`() = runTest {
        ledger.recordSent("r1", "user-a")
        ledger.recordSent("r2", "user-b")
        assertEquals("user-a", ledger.sentTo("r1"))
        ledger.forgetSent("r1")
        assertNull(ledger.sentTo("r1"))
        assertEquals("user-b", ledger.sentTo("r2"))
    }

    @Test
    fun `both survive a restart, in the encrypted store`() = runTest {
        ledger.recordSent("r1", "user-a")
        ledger.ack("r9")
        val again = ContactRequestLedger(blobs)
        assertEquals("user-a", again.sentTo("r1"))
        assertEquals(listOf("r9"), again.pendingAcks())
    }
}
