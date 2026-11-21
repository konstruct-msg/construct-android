package com.construct.messenger.data.repository

import com.construct.messenger.data.repository.ChatSendLanes.Lane
import com.construct.messenger.domain.usecase.SendOutcome
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Todo 321: a send takes its place when it is written and leaves on its turn; a queued one ahead
 * keeps its place (the owner, 2026-11-21 — as Signal).
 */
class ChatSendLanesTest {

    /** A hang is a failure, not a stuck run: a lane that never frees fails here in ten seconds. */
    private fun bounded(body: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) =
        runBlocking<Unit> { withTimeout(10_000) { body() } }

    private val peer = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
    private val events: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private suspend fun until(vararg wanted: String) = withTimeout(5_000) {
        while (!events.containsAll(wanted.toList())) delay(10)
    }

    private fun sent(name: String) = SendOutcome.Sent(name)

    /**
     * The row and the upload of the second go while the first is still on its way; only its send
     * waits. Mutation: hold the place from before the row — "second shown" waits for "first left".
     */
    @Test
    fun `a later send is shown at once and leaves after the one before it`() = bounded {
        val lanes = ChatSendLanes()
        val firstOnItsWay = CompletableDeferred<Unit>()

        val first = async {
            lanes.inOrder(peer, Lane.TEXT) { turn ->
                turn.go { events += "first leaves"; firstOnItsWay.await(); events += "first left"; sent("first") }
            }
        }
        until("first leaves")
        val second = async {
            lanes.inOrder(peer, Lane.TEXT) { turn ->
                events += "second shown"
                turn.go { events += "second left"; sent("second") }
            }
        }
        until("second shown")
        delay(200)
        assertTrue("second left" !in events)

        firstOnItsWay.complete(Unit)
        first.await()
        second.await()
        assertEquals(listOf("first leaves", "second shown", "first left", "second left"), events.toList())
    }

    /**
     * A send that ends without leaving (its upload failed) gives its place back only after the one
     * ahead of it: the one behind does not skip a send still on its way. Mutation: give it back at
     * once.
     */
    @Test
    fun `a send that never left does not let the next one skip the one before it`() = bounded {
        val lanes = ChatSendLanes()
        val firstOnItsWay = CompletableDeferred<Unit>()

        val first = async {
            lanes.inOrder(peer, Lane.MEDIA) { turn ->
                turn.go { events += "first leaves"; firstOnItsWay.await(); events += "first left"; sent("first") }
            }
        }
        until("first leaves")
        lanes.inOrder(peer, Lane.MEDIA) { SendOutcome.Failed("second", "upload failed") }
        val third = async { lanes.inOrder(peer, Lane.MEDIA) { turn -> turn.go { events += "third left"; sent("third") } } }
        delay(200)
        assertTrue("third left" !in events)

        firstOnItsWay.complete(Unit)
        first.await()
        third.await()
        assertEquals(listOf("first leaves", "first left", "third left"), events.toList())
    }

    /**
     * A queued one ahead keeps its place: the turn waits for the hold to clear, and only that lane
     * does. Mutation: leave without asking the hold.
     */
    @Test
    fun `a send waits while the hold says a queued one is ahead`() = bounded {
        val cleared = CompletableDeferred<Unit>()
        val lanes = ChatSendLanes { _, lane -> if (lane == Lane.TEXT) cleared.await() }

        val text = async { lanes.inOrder(peer, Lane.TEXT) { turn -> turn.go { events += "text left"; sent("text") } } }
        val photo = async { lanes.inOrder(peer, Lane.MEDIA) { turn -> turn.go { events += "photo left"; sent("photo") } } }
        photo.await()
        delay(200)
        assertEquals(listOf("photo left"), events.toList())

        cleared.complete(Unit)
        text.await()
        assertEquals(listOf("photo left", "text left"), events.toList())
    }

    /** Mutation: put a sticker in the media lane — it would wait on an upload. */
    @Test
    fun `a sticker and a text share a lane, media has its own`() {
        assertEquals(Lane.TEXT, Lane.of(null))
        assertEquals(Lane.TEXT, Lane.of(com.construct.messenger.util.MediaWire.KIND_STICKER))
        assertEquals(Lane.MEDIA, Lane.of(com.construct.messenger.util.MediaWire.KIND_ALBUM))
        assertEquals(Lane.MEDIA, Lane.of(com.construct.messenger.util.MediaWire.KIND_VOICE))
    }
}
