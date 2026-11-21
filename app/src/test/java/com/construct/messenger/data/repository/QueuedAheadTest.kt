package com.construct.messenger.data.repository

import com.construct.messenger.data.local.FakeMessageStore
import com.construct.messenger.data.local.FakeSessionBlobStore
import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.data.local.MessageRecord
import com.construct.messenger.data.model.DeliveryStatus
import com.construct.messenger.data.repository.ChatSendLanes.Lane
import com.construct.messenger.service.FanoutRetryQueue
import com.construct.messenger.util.ConversationId
import com.construct.messenger.util.MediaWire
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import com.construct.messenger.service.FanoutRetries
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Todo 321, the owner 2026-11-21: a queued message keeps its place, as Signal's retry does — the
 * next send to that person waits until it has left or failed.
 */
class QueuedAheadTest {

    /** A hang is a failure, not a stuck run: a lane that never frees fails here in ten seconds. */
    private fun bounded(body: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) =
        runBlocking<Unit> { withTimeout(10_000) { body() } }

    private val me = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
    private val peer = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
    private val chat = ConversationId.direct(me, peer)
    private val queuedId = "11111111-1111-4111-8111-111111111111"

    private fun row(
        status: DeliveryStatus = DeliveryStatus.QUEUED,
        chatId: String = chat,
        mine: Boolean = true,
        mediaType: String? = null,
    ) = MessageRecord(queuedId, chatId, "hi", mine, 1L, status, mediaType = mediaType)

    private fun holds(row: MessageRecord, lane: Lane = Lane.TEXT, late: Boolean = false) =
        QueuedAhead.holding(listOf(QueuedAhead.Ahead(row, late)), chat, lane)?.id == queuedId

    /** Mutations, one each: no QUEUED test, no lane test, no chat test, no sender test. */
    @Test
    fun `only our queued message in that chat and lane holds it`() {
        assertTrue(holds(row()))
        assertFalse(holds(row(status = DeliveryStatus.SENT)))
        assertFalse(holds(row(status = DeliveryStatus.FAILED)))
        assertFalse(holds(row(mediaType = MediaWire.KIND_ALBUM)))
        assertTrue(holds(row(mediaType = MediaWire.KIND_ALBUM), Lane.MEDIA))
        assertFalse(holds(row(chatId = ConversationId.direct(me, me))))
        assertFalse(holds(row(mine = false)))
    }

    /**
     * One device took it, another is owed a copy the path cut off: it holds, as Signal's job does
     * until every device has it. Mutation: hold on QUEUED only. A refused copy does not — the
     * other case.
     */
    @Test
    fun `a sent message holds while a late copy is owed`() {
        assertTrue(holds(row(status = DeliveryStatus.SENT), late = true))
        assertFalse(holds(row(status = DeliveryStatus.SENT), late = false))
        assertFalse(holds(row(status = DeliveryStatus.SENT, chatId = ConversationId.direct(me, me)), late = true))
    }

    private val messages = FakeMessageStore()
    private val queue = FanoutRetryQueue(FakeSessionBlobStore())
    private val keystore: KeystoreManager = mock<KeystoreManager>().also { whenever(it.getUserId()).thenReturn(me) }
    private val hold = QueuedAhead(queue, FanoutRetries(queue, { mock() }, CoroutineScope(Job())) { 0L }, messages, keystore)

    /**
     * The send waits while the retry owes the queued one, and goes once the entry is gone — the
     * queue's change wakes it. Mutation: the queue does not say it changed — the send never goes.
     */
    @Test
    fun `a send waits for the queued one ahead to leave the retry queue`() = bounded {
        messages.rows[queuedId] = row()
        queue.enqueue(queuedId, peer, me, listOf("dev-1"))

        val waiting = async { hold.awaitClear(peer, Lane.TEXT) }
        delay(200)
        assertFalse(waiting.isCompleted)

        queue.remove("$queuedId|$peer")
        withTimeout(5_000) { waiting.await() }
    }

    /** A QUEUED row the retry no longer owes holds nothing — or it would hold the chat for good. Mutation: hold on the row alone. */
    @Test
    fun `a queued row with no entry in the retry queue holds nothing`() = bounded {
        messages.rows[queuedId] = row()
        withTimeout(1_000) { hold.awaitClear(peer, Lane.TEXT) }
    }
}
