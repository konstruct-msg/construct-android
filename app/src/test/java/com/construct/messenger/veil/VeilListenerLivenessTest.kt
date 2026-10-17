package com.construct.messenger.veil

import java.io.FileDescriptor
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Conscrypt tunnel's listener, and who answers whether it lives. A tester's log (2026-10-16)
 * showed the listener dying with `EBADF` and the route finding out 82 s later from a silent stream.
 */
class VeilListenerLivenessTest {

    /** Mutation: drop the `onLost` call in the catch — reddens. */
    @Test
    fun `a listener that dies while running is reported once`() {
        val lost = mutableListOf<Exception>()
        runAcceptLoop(
            isRunning = { true },
            accept = { throw IOException("getsockopt failed: EBADF") },
            handle = { true },
            onLost = { lost += it },
        )
        assertEquals(1, lost.size)
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
