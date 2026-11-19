package com.construct.messenger.veil

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which side ended a ferry session: the reason the end line names (todo 319). */
class FerryPumpTest {

    private var now = 0L
    private val stats = FerrySessionStats { now }

    private fun reads(vararg results: Any): () -> Int {
        val queue = ArrayDeque(results.toList())
        return {
            when (val r = queue.removeFirst()) {
                is Int -> r
                is Exception -> throw r
                else -> error("bad script")
            }
        }
    }

    @Test
    fun `relay EOF is relay-closed, and what came before is counted down`() {
        val written = mutableListOf<Int>()
        val end = pumpFromRelay(reads(100, 0, 20, -1), { written += it }, stats)

        assertEquals(FerrySessionEnd.RELAY_CLOSED, end)
        assertEquals(listOf(100, 20), written)
        assertTrue(stats.end(end)!!.contains("down=120B"))
    }

    @Test
    fun `a failed relay read is relay-error`() {
        assertEquals(FerrySessionEnd.RELAY_ERROR, pumpFromRelay(reads(10, IOException("reset")), {}, stats))
    }

    @Test
    fun `a failed write to gRPC on the way down is local-closed`() {
        assertEquals(
            FerrySessionEnd.LOCAL_CLOSED,
            pumpFromRelay(reads(10), { throw IOException("EPIPE") }, stats),
        )
    }

    @Test
    fun `socketpair EOF is local-closed, and what went before is counted up`() {
        val sent = mutableListOf<Int>()
        val end = pumpToRelay(reads(300, 40, 0), { sent += it }, stats)

        assertEquals(FerrySessionEnd.LOCAL_CLOSED, end)
        assertEquals(listOf(300, 40), sent)
        assertTrue(stats.end(end)!!.contains("up=340B down=0B"))
    }

    @Test
    fun `a failed socketpair read is local-closed`() {
        assertEquals(FerrySessionEnd.LOCAL_CLOSED, pumpToRelay(reads(IOException("EBADF")), {}, stats))
    }

    @Test
    fun `a failed write to the relay is send-failed, and the unsent bytes are not counted`() {
        val end = pumpToRelay(reads(50), { throw IOException("broken pipe") }, stats)

        assertEquals(FerrySessionEnd.SEND_FAILED, end)
        assertTrue(stats.end(end)!!.contains("up=0B"))
    }
}
