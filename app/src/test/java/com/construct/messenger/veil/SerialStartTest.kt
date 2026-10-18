package com.construct.messenger.veil

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** TODO 164 item 4: a superseded tunnel start takes down only what it brought up. */
class SerialStartTest {

    /**
     * The leak: a start cancelled while its blocking dial runs. Mutation: a plain
     * `withContext(Dispatchers.IO)` — it throws on the way out and nothing is torn down.
     */
    @Test
    fun `a dial cancelled while it runs tears down what it brought up`() = runBlocking {
        val dialing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val tornDown = Collections.synchronizedList(mutableListOf<Int>())
        val start = async(Dispatchers.Default) {
            dialKeepingResult({ dialing.countDown(); release.await(5, TimeUnit.SECONDS); 41713 }) { tornDown += it }
        }
        assertTrue(dialing.await(5, TimeUnit.SECONDS))
        start.cancel()
        release.countDown()
        start.join() // await() on a cancelled Deferred throws at once; join waits for the dial to end
        val thrown = runCatching { start.await() }.exceptionOrNull()
        assertTrue("the cancellation goes on: $thrown", thrown is CancellationException)
        assertEquals(listOf(41713), tornDown)
    }

    @Test
    fun `a dial left alone returns its result and tears nothing down`() = runBlocking {
        var tornDown = 0
        assertEquals(7, dialKeepingResult({ 7 }) { tornDown++ })
        assertEquals(null, dialKeepingResult<Int>({ null }) { tornDown++ })
        assertEquals(0, tornDown)
    }

    /** Mutation: drop the `ensureActive` — a cancelled start would still bind a listener. */
    @Test
    fun `a start cancelled before its dial does not dial`() = runBlocking {
        var dialed = false
        val job = launch(Dispatchers.Default) {
            this.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
            dialKeepingResult({ dialed = true; 1 }) {}
        }
        job.join()
        assertEquals(false, dialed)
    }

    /**
     * Two starts: the second waits for the first, and begins by taking down what the first brought
     * up. Mutations: no lock — the second dials while the first holds the dialer; no `stopCurrent`
     * — the first's listener is overwritten and left open.
     */
    @Test
    fun `starts run one at a time, each replacing what is up`() = runBlocking {
        val log = Collections.synchronizedList(mutableListOf<String>())
        val starts = SerialStart(stopCurrent = { log += "stop" })
        val firstMayFinish = CompletableDeferred<Unit>()
        val first = async(Dispatchers.Default) { starts.run { log += "dial 1"; firstMayFinish.await(); log += "up 1"; 1 } }
        withTimeout(5_000) { while ("dial 1" !in log) yield() }
        val second = async(Dispatchers.Default) { starts.run { log += "dial 2"; 2 } }
        repeat(50) { yield() }
        assertEquals("the second waits", listOf("stop", "dial 1"), log.toList())
        firstMayFinish.complete(Unit)
        assertEquals(1, first.await())
        assertEquals(2, second.await())
        assertEquals(listOf("stop", "dial 1", "up 1", "stop", "dial 2"), log.toList())
    }
}
