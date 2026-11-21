package com.construct.messenger.data.repository

import com.construct.messenger.domain.usecase.SendMediaUseCase
import com.construct.messenger.domain.usecase.SendMessageUseCase
import com.construct.messenger.domain.usecase.SendOutcome
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
 * to one recipient through one job queue (`IndividualSendJob`, `recipient.id.toQueueKey(hasMedia)`),
 * media in a queue of its own beside it — so does this.
 *
 * Mutations that redden it: no queue (the second send starts while the first hangs); one queue for
 * every chat (a send to someone else waits); one lane for text and media (a text waits on an upload).
 */
class SendsKeepTheirOrderTest {

    private val peer = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
    private val other = "cccccccc-cccc-cccc-cccc-cccccccccccc"

    private val sendMessage: SendMessageUseCase = mock()
    private val sendMedia: SendMediaUseCase = mock()
    private val started: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val held = CompletableDeferred<Unit>()

    private fun repository() = MessagesRepositoryImpl(
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
    )

    /** A send of [text] records that it started; the one named "first" hangs until [held]. */
    private suspend fun texts() {
        whenever(sendMessage.invoke(any(), any(), anyOrNull())).doSuspendableAnswer { call ->
            val text = call.arguments[1] as String
            started += "${call.arguments[0]}:$text"
            if (text == "first") held.await()
            SendOutcome.Failed(text, "test")
        }
    }

    private suspend fun untilStarted(vararg sends: String) = withTimeout(5_000) {
        while (!started.containsAll(sends.toList())) delay(10)
    }

    @Test
    fun `a later message to the same person waits for the one before it`() = runBlocking<Unit> {
        texts()
        val repo = repository()

        val first = async { repo.send(peer, "first", null) }
        untilStarted("$peer:first")
        val second = async { repo.send(peer, "second", null) }
        delay(300)

        assertEquals(listOf("$peer:first"), started.toList())
        held.complete(Unit)
        first.await()
        second.await()
        assertEquals(listOf("$peer:first", "$peer:second"), started.toList())
    }

    @Test
    fun `a message to someone else does not wait`() = runBlocking<Unit> {
        texts()
        val repo = repository()

        val first = async { repo.send(peer, "first", null) }
        untilStarted("$peer:first")
        val elsewhere = async { repo.send(other, "second", null) }

        untilStarted("$other:second")
        held.complete(Unit)
        first.await()
        elsewhere.await()
    }

    @Test
    fun `a text does not wait on a photo being uploaded`() = runBlocking<Unit> {
        texts()
        whenever(sendMedia.photos(any(), any(), any(), anyOrNull(), any())).doSuspendableAnswer {
            started += "$peer:photo"
            held.await()
            SendOutcome.Failed("photo", "test")
        }
        val repo = repository()

        val photo = async { repo.sendPhotos(peer, emptyList(), "", null, com.construct.messenger.media.VideoQuality.entries.first()) }
        untilStarted("$peer:photo")
        val text = async { repo.send(peer, "second", null) }

        untilStarted("$peer:second")
        held.complete(Unit)
        photo.await()
        text.await()
    }
}
