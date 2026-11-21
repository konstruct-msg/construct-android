package com.construct.messenger.data.repository

import com.construct.messenger.data.local.ChatRecord
import com.construct.messenger.data.local.FakeChatStore
import com.construct.messenger.data.local.FakeMessageStore
import com.construct.messenger.data.local.MessageRecord
import com.construct.messenger.data.model.DeliveryStatus
import com.construct.messenger.domain.usecase.SendMediaUseCase
import com.construct.messenger.domain.usecase.SendOutcome
import com.construct.messenger.domain.usecase.SendTurn
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Todo 321: a send a previous process left on its way goes again at the next start — its wait for
 * a turn lived in that process's memory, and its row would have stayed SENDING with no retry.
 * **Canon:** iOS `MessageQueueManager.resetStuckSendingMessages`.
 */
class OrphanedSendsTest {

    private val peer = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
    private val chat = "chat-b"
    private val messages = FakeMessageStore()
    private val chats = FakeChatStore()
    private val sendMedia: SendMediaUseCase = mock()
    private val left: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val statusAtRetry: MutableMap<String, DeliveryStatus?> = Collections.synchronizedMap(mutableMapOf())

    private fun repository() = MessagesRepositoryImpl(
        messages = messages,
        keystoreManager = mock(),
        sendMessage = mock(),
        chats = chats,
        presence = mock(),
        alerts = mock(),
        sendContactCard = mock(),
        sendMedia = sendMedia,
        media = mock(),
        pickedFiles = mock(),
        reactions = mock(),
        stickers = mock(),
        readSync = mock(),
        mediaPreview = mock(),
        queuedAhead = mock(),
    )

    private fun row(id: String, at: Long, status: DeliveryStatus = DeliveryStatus.SENDING) =
        MessageRecord(id, chat, "m-$id", isSentByMe = true, timestampMs = at, deliveryStatus = status)

    /** The first resend hangs until [firstHeld]; each records the row's status when it was asked to retry. */
    private suspend fun retries(firstHeld: CompletableDeferred<Unit>? = null) {
        whenever(sendMedia.retry(any(), any(), any())).doSuspendableAnswer { call ->
            val id = call.arguments[1] as String
            statusAtRetry[id] = messages.get(id)?.deliveryStatus
            (call.arguments[2] as SendTurn).go {
                if (id == "a") firstHeld?.await()
                left += id
                SendOutcome.Sent(id)
            }
        }
    }

    /**
     * The orphans go again, as retries — FAILED first, which is what a retry sends — in the
     * transcript order, and ours only. Mutations: no resume at all; not FAILED first (the retry
     * refuses a SENDING row); places taken as each runs (the second overtakes the hung first).
     */
    @Test
    fun `sends a previous process left go again, in order`() = runBlocking<Unit> {
        withTimeout(10_000) {
            chats.insert(ChatRecord(chat, peer))
            messages.insert(row("b", 2))
            messages.insert(row("a", 1))
            messages.insert(row("c", 3, DeliveryStatus.SENT))
            messages.insert(row("d", 4).copy(isSentByMe = false))
            val firstHeld = CompletableDeferred<Unit>()
            retries(firstHeld)
            val repo = repository()

            val resume = async { repo.resumeOrphanedSends() }
            delay(300)
            assertEquals(emptyList<String>(), left.toList())
            firstHeld.complete(Unit)
            resume.await()

            assertEquals(listOf("a", "b"), left.toList())
            assertEquals(mapOf("a" to DeliveryStatus.FAILED, "b" to DeliveryStatus.FAILED), statusAtRetry.toMap())
        }
    }

    /** A row this process wrote is its own send, not an orphan; and the resume runs once. Mutations: no age test; no once. */
    @Test
    fun `a send of this process is not an orphan, and the resume runs once`() = runBlocking<Unit> {
        withTimeout(10_000) {
            chats.insert(ChatRecord(chat, peer))
            retries()
            val repo = repository()
            messages.insert(row("new", System.currentTimeMillis() + 60_000))
            messages.insert(row("old", 1))

            repo.resumeOrphanedSends()
            messages.insert(row("older", 0))
            repo.resumeOrphanedSends()

            assertEquals(listOf("old"), left.toList())
            assertEquals(DeliveryStatus.SENDING, messages.get("new")?.deliveryStatus)
        }
    }
}
