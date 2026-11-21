package com.construct.messenger.data.repository

import com.construct.messenger.domain.usecase.SendMediaUseCase
import com.construct.messenger.domain.usecase.SendMessageUseCase
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
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Todo 321: messages to one person leave one at a time, in the order they were written.
 *
 * Each send used to be a `scope.async` of its own, and the server orders a chat by arrival: a send
 * held up on the key server or a dead connection arrived after the ones written later. Signal sends
 * to one recipient through one job queue (`IndividualSendJobV2`, `recipient.id.toQueueKey(hasMedia)`),
 * media in a queue of its own beside it — so does this.
 *
 * Mutations that redden it: no queue (the second send leaves while the first hangs); one queue for
 * every chat (a send to someone else waits); one lane for text and media (a text waits on an upload).
 */
class SendsKeepTheirOrderTest {

    /** A hang is a failure, not a stuck run: a lane that never frees fails here in ten seconds. */
    private fun bounded(body: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) =
        runBlocking<Unit> { withTimeout(10_000) { body() } }

    private val peer = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
    private val other = "cccccccc-cccc-cccc-cccc-cccccccccccc"

    private val sendMessage: SendMessageUseCase = mock()
    private val sendMedia: SendMediaUseCase = mock()
    private val left: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val held = CompletableDeferred<Unit>()

    private fun repository(queuedAhead: QueuedAhead = mock()) = MessagesRepositoryImpl(
        messages = mock(),
        keystoreManager = mock(),
        sendMessage = sendMessage,
        chats = mock(),
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
        queuedAhead = queuedAhead,
    )

    /** A send of [text] records that it left, on its turn; the one named "first" hangs until [held]. */
    private suspend fun texts() {
        whenever(sendMessage.invoke(any(), any(), anyOrNull(), any())).doSuspendableAnswer { call ->
            val text = call.arguments[1] as String
            (call.arguments[3] as SendTurn).go {
                left += "${call.arguments[0]}:$text"
                if (text == "first") held.await()
                SendOutcome.Failed(text, "test")
            }
        }
    }

    private suspend fun untilLeft(vararg sends: String) = withTimeout(5_000) {
        while (!left.containsAll(sends.toList())) delay(10)
    }

    @Test
    fun `a later message to the same person waits for the one before it`() = bounded {
        texts()
        val repo = repository()

        val first = async { repo.send(peer, "first", null) }
        untilLeft("$peer:first")
        val second = async { repo.send(peer, "second", null) }
        delay(300)

        assertEquals(listOf("$peer:first"), left.toList())
        held.complete(Unit)
        first.await()
        second.await()
        assertEquals(listOf("$peer:first", "$peer:second"), left.toList())
    }

    @Test
    fun `a message to someone else does not wait`() = bounded {
        texts()
        val repo = repository()

        val first = async { repo.send(peer, "first", null) }
        untilLeft("$peer:first")
        val elsewhere = async { repo.send(other, "second", null) }

        untilLeft("$other:second")
        held.complete(Unit)
        first.await()
        elsewhere.await()
    }

    @Test
    fun `a text does not wait on a photo being uploaded`() = bounded {
        texts()
        whenever(sendMedia.photos(any(), any(), any(), anyOrNull(), any(), any())).doSuspendableAnswer { call ->
            (call.arguments[5] as SendTurn).go {
                left += "$peer:photo"
                held.await()
                SendOutcome.Failed("photo", "test")
            }
        }
        val repo = repository()

        val photo = async { repo.sendPhotos(peer, emptyList(), "", null, com.construct.messenger.media.VideoQuality.entries.first()) }
        untilLeft("$peer:photo")
        val text = async { repo.send(peer, "second", null) }

        untilLeft("$peer:second")
        held.complete(Unit)
        photo.await()
        text.await()
    }

    /** A queued message ahead keeps its place (the owner, 2026-11-21). Mutation: lanes built without the hold. */
    @Test
    fun `a send waits while a queued one is ahead of it`() = bounded {
        texts()
        val cleared = CompletableDeferred<Unit>()
        val queuedAhead: QueuedAhead = mock()
        whenever(queuedAhead.awaitClear(any(), any())).doSuspendableAnswer { cleared.await() }
        val repo = repository(queuedAhead)

        val send = async { repo.send(peer, "second", null) }
        delay(300)
        assertEquals(emptyList<String>(), left.toList())

        cleared.complete(Unit)
        send.await()
        assertEquals(listOf("$peer:second"), left.toList())
    }
}
