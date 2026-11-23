package com.construct.messenger.veil

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Canon iOS `HedgedAttemptTests` / `VeilSilentFrontTests`. */
class DialRaceTest {
    private class Conn : Closeable {
        private val gone = CountDownLatch(1)
        val closed: Boolean get() = gone.count == 0L
        override fun close() = gone.countDown()

        /** A handshake nothing answers: only a close ends it. */
        fun silent(): Nothing {
            assertTrue("never closed", gone.await(20, TimeUnit.SECONDS))
            throw IOException("Socket closed")
        }
    }

    private val discarded: MutableList<Conn> = Collections.synchronizedList(mutableListOf())
    private fun race(silenceMs: Long = 50, totalMs: Long = 5_000) = DialRace<Conn>(silenceMs, totalMs, discard = { discarded += it })

    private fun tookMs(block: () -> Unit): Long {
        val t = System.nanoTime()
        block()
        return (System.nanoTime() - t) / 1_000_000
    }

    /** An answer before the silence is the only dial. Mutation: start the second at once. */
    @Test
    fun anAnswerInTimeIsTheOnlyDial() {
        val dials = AtomicInteger()
        val conn = Conn()
        val won = race(silenceMs = 10_000).run { opened -> dials.incrementAndGet(); opened(conn); conn }
        assertSame(conn, won)
        Thread.sleep(50)
        assertEquals(1, dials.get())
        assertTrue(!conn.closed)
    }

    /** Silence: the second dial's answer wins and the first is closed. Mutations: never dial again; leave the loser open. */
    @Test
    fun aSilentDialIsReplacedAndClosed() {
        val conns = List(2) { Conn() }
        val n = AtomicInteger()
        val won = race().run { opened ->
            val i = n.getAndIncrement()
            opened(conns[i])
            if (i == 0) conns[0].silent() else conns[1]
        }
        assertSame(conns[1], won)
        assertTrue(conns[0].closed)
        assertTrue(!conns[1].closed)
    }

    /** The first may still win. Mutation: drop it once the second runs. */
    @Test
    fun aLateFirstAnswerStillWins() {
        val conns = List(2) { Conn() }
        val n = AtomicInteger()
        val won = race().run { opened ->
            val i = n.getAndIncrement()
            opened(conns[i])
            if (i == 1) conns[1].silent()
            Thread.sleep(200)
            conns[0]
        }
        assertSame(conns[0], won)
        assertTrue(conns[1].closed)
    }

    /** A failure is an answer, at once. Mutations: dial again after one; wait the silence out. */
    @Test
    fun aFailureInTimeIsTheAnswer() {
        val dials = AtomicInteger()
        val ms = tookMs {
            assertThrows(IOException::class.java) { race(silenceMs = 10_000, totalMs = 20_000).run { dials.incrementAndGet(); throw IOException("refused") } }
        }
        assertTrue("$ms ms", ms < 3_000)
        assertEquals(1, dials.get())
    }

    /** One failing of two leaves the other; the first error is the answer when both fail. Mutation: throw at the first. */
    @Test
    fun oneFailureOfTwoLeavesTheOther() {
        val n = AtomicInteger()
        val conn = Conn()
        val won = race().run { _ ->
            if (n.getAndIncrement() == 0) {
                Thread.sleep(120)
                throw IOException("first")
            }
            Thread.sleep(250)
            conn
        }
        assertSame(conn, won)

        val both = AtomicInteger()
        val error = assertThrows(IOException::class.java) {
            race().run { _ ->
                val i = both.getAndIncrement()
                Thread.sleep(if (i == 0) 120L else 200L)
                throw IOException("dial $i")
            }
        }
        assertEquals("dial 0", error.message)
    }

    /** Two dials nothing answers end at the total, both closed. Mutation: wait for an answer without a bound. */
    @Test
    fun silenceOnBothEndsAtTheTotal() {
        val conns = List(2) { Conn() }
        val n = AtomicInteger()
        val ms = tookMs {
            assertThrows(SocketTimeoutException::class.java) {
                race(silenceMs = 50, totalMs = 300).run { opened -> conns[n.getAndIncrement()].also { opened(it) }.silent() }
            }
        }
        assertTrue("$ms ms", ms in 250..2_000)
        assertTrue(conns.all { it.closed })
    }

    /** An answer that comes after the race is discarded, and a socket opened after it is closed. Mutation: keep either. */
    @Test
    fun whatComesAfterTheRaceIsLetGo() {
        val late = Conn()
        val lateSocket = Conn()
        val n = AtomicInteger()
        race().run { opened ->
            if (n.getAndIncrement() == 0) {
                // A name lookup cannot be woken: it ends after the race.
                Thread.sleep(300)
                opened(lateSocket)
                late
            } else {
                Conn()
            }
        }
        Thread.sleep(600)
        assertTrue(lateSocket.closed)
        assertEquals(listOf(late), discarded.toList())
    }

    /** A listener that accepts TCP and says nothing is dialed twice and given up on. */
    @Test
    fun aSilentListenerIsDialedTwiceAndGivenUpOn() {
        ServerSocket(0, 16, InetAddress.getLoopbackAddress()).use { server ->
            val ms = tookMs {
                assertThrows(SocketTimeoutException::class.java) {
                    DialRace<Socket>(200, 600, discard = { it.close() }).run { opened ->
                        Socket().also { s ->
                            opened(s)
                            s.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort), 600)
                            check(s.getInputStream().read() >= 0)
                        }
                    }
                }
            }
            assertTrue("$ms ms", ms in 500..3_000)
            server.soTimeout = 200
            repeat(2) { server.accept().close() }
            assertThrows(SocketTimeoutException::class.java) { server.accept() }
        }
    }

    /** The dialer's handshake goes through the race: there is no other bound on it. Mutation: call `dialOnce` directly. */
    @Test
    fun theDialersHandshakeIsRaced() {
        val source = java.io.File("src/main/java/com/construct/messenger/veil/VeilFrontExternalDialer.kt").readText()
        assertEquals(1, Regex("""dialOnce\(r, opened\)""").findAll(source).count())
        assertEquals(1, Regex("""startHandshake\(\)""").findAll(source).count())
        assertTrue(source.contains(").run { opened -> dialOnce(r, opened) }"))
        assertTrue(source.contains("opened(ssl)\n"))
    }
}
