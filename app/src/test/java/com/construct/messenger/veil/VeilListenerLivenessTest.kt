package com.construct.messenger.veil

import java.io.FileDescriptor
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Conscrypt tunnel's listener, and who answers whether it lives. A tester's log (2026-10-16)
 * showed `getsockopt failed: EBADF` from accept — libcore's check of the *accepted* socket, with
 * the listener fine (TODO 164).
 */
class VeilListenerLivenessTest {

    /** Mutation: drop the `onLost` call in the catch — reddens. */
    @Test
    fun `a listener that dies while running is reported once`() {
        val lost = mutableListOf<Exception>()
        runAcceptLoop(
            isRunning = { true },
            accept = { throw IOException("accept failed: EBADF") },
            handle = { true },
            listenerAlive = { false },
            onLost = { lost += it },
            pause = {},
        )
        assertEquals(1, lost.size)
    }

    /**
     * The tester's case: one accepted connection goes bad, the listener does not. Mutation: the
     * old loop — any throw while running is `onLost` — reddens.
     */
    @Test
    fun `one connection lost leaves the listener accepting`() {
        var calls = 0
        var handed = 0
        var lost = 0
        val dropped = mutableListOf<Exception>()
        runAcceptLoop(
            isRunning = { true },
            accept = { if (++calls == 2) throw IOException("getsockopt failed: EBADF") else FileDescriptor() },
            handle = { ++handed < 3 },
            listenerAlive = { true },
            onLost = { lost++ },
            onDropped = { dropped += it },
            pause = {},
        )
        assertEquals(0, lost)
        assertEquals(1, dropped.size)
        assertEquals("accepting went on after the throw", 3, handed)
    }

    /** Mutation: no pause, or one that does not grow or does not reset — a recurring fault would spin. */
    @Test
    fun `repeated throws back off, and a good accept starts over`() {
        val script = ArrayDeque(listOf(false, false, false, true, false, true, true))
        val pauses = mutableListOf<Long>()
        runAcceptLoop(
            isRunning = { script.isNotEmpty() },
            accept = { if (script.removeFirst()) FileDescriptor() else throw IOException("x") },
            handle = { true },
            listenerAlive = { true },
            onLost = { error("listener is alive") },
            pause = { pauses += it },
        )
        assertEquals(listOf(10L, 20L, 40L, 10L), pauses)
        assertEquals(1_000L, acceptBackoffMs(30))
    }

    /** Mutation: report without asking `isRunning` again — stop()'s own close reddens this. */
    @Test
    fun `the close a stop does is not a loss`() {
        var running = true
        var lost = 0
        runAcceptLoop(
            isRunning = { running },
            accept = { running = false; throw IOException("closed") },
            handle = { true },
            listenerAlive = { false },
            onLost = { lost++ },
        )
        assertEquals(0, lost)
    }

    @Test
    fun `connections are handed on until the handler says stop`() {
        var accepted = 0
        var lost = 0
        runAcceptLoop(
            isRunning = { true },
            accept = { FileDescriptor() },
            handle = { ++accepted < 3 },
            listenerAlive = { true },
            onLost = { lost++ },
        )
        assertEquals(3, accepted)
        assertEquals(0, lost)
    }

    /**
     * The coordinator is never started on the Conscrypt path, so it says "dead" for a healthy
     * tunnel. Mutation: ask the coordinator whatever the path — reddens.
     */
    @Test
    fun `a native tunnel is alive while its listener is`() {
        assertTrue(tunnelAlive(nativeUp = true, nativeListening = true) { false })
        assertFalse(tunnelAlive(nativeUp = true, nativeListening = false) { true })
    }

    @Test
    fun `a rustls tunnel asks the coordinator`() {
        assertTrue(tunnelAlive(nativeUp = false, nativeListening = false) { true })
        assertFalse(tunnelAlive(nativeUp = false, nativeListening = true) { false })
    }
}
