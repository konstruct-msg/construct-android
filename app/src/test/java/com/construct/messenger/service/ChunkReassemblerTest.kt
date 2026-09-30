package com.construct.messenger.service

import com.construct.messenger.util.KnstFrame
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkReassemblerTest {

    private val dao = FakePendingChunkDao()
    private val reassembler = ChunkReassembler(dao)
    private val id = UUID.fromString("12345678-1234-4234-8234-123456789abc")
    private val sender = "sender"

    private fun payload(size: Int) = ByteArray(size) { (it % 251).toByte() }

    private fun ready(a: ChunkReassembler.Assembly): ByteArray {
        assertTrue("expected Ready, got $a", a is ChunkReassembler.Assembly.Ready)
        return (a as ChunkReassembler.Assembly.Ready).plaintext
    }

    @Test
    fun `a single frame passes through untouched`() = runTest {
        val frame = KnstFrame.pack(payload(10), KnstFrame.TYPE_E2EE_SIGNAL, id)
        assertSame(frame, ready(reassembler.accept(sender, frame)))
        val raw = "legacy".toByteArray()
        assertSame(raw, ready(reassembler.accept(sender, raw)))
    }

    /** Out of order and with a duplicate: the joined frame is the one a single send would be. */
    @Test
    fun `chunks in any order become the frame a single send would be`() = runTest {
        val body = payload(3770 * 2 + 5)
        val frames = KnstFrame.chunks(body, KnstFrame.TYPE_E2EE_SIGNAL, id)
        assertEquals(3, frames.size)

        assertEquals(ChunkReassembler.Assembly.Pending, reassembler.accept(sender, frames[2]))
        assertEquals(ChunkReassembler.Assembly.Pending, reassembler.accept(sender, frames[0]))
        assertEquals(ChunkReassembler.Assembly.Pending, reassembler.accept(sender, frames[0]))
        val whole = ready(reassembler.accept(sender, frames[1]))

        assertArrayEquals(KnstFrame.whole(body, KnstFrame.TYPE_E2EE_SIGNAL, id), whole)
        assertTrue(dao.rows.isEmpty())
    }

    /** Another account's frames under the same id cannot complete, or be completed by, ours. */
    @Test
    fun `chunks from two senders never mix`() = runTest {
        val frames = KnstFrame.chunks(payload(5000), KnstFrame.TYPE_E2EE_SIGNAL, id)
        reassembler.accept(sender, frames[0])
        assertEquals(ChunkReassembler.Assembly.Pending, reassembler.accept("other", frames[1]))
        assertEquals(2, dao.rows.size)
    }

    @Test
    fun `a partial older than a day is dropped`() = runTest {
        val frames = KnstFrame.chunks(payload(5000), KnstFrame.TYPE_E2EE_SIGNAL, id)
        reassembler.accept(sender, frames[0], nowMs = 0L)
        assertEquals(
            ChunkReassembler.Assembly.Pending,
            reassembler.accept(sender, frames[1], nowMs = ChunkReassembler.RETENTION_MS + 1),
        )
        assertEquals(listOf(1), dao.rows.map { it.chunkIndex })
    }

    @Test
    fun `a header that cannot be true is refused`() = runTest {
        val frame = KnstFrame.chunks(payload(5000), KnstFrame.TYPE_E2EE_SIGNAL, id)[0].copyOf()
        frame[22] = 0; frame[23] = 5 // chunk 5 of 2
        assertTrue(reassembler.accept(sender, frame) is ChunkReassembler.Assembly.Invalid)
        assertTrue(dao.rows.isEmpty())
    }

    /** A second message reusing the id with another shape replaces what was held, not joins it. */
    @Test
    fun `frames that disagree on the header start over`() = runTest {
        reassembler.accept(sender, KnstFrame.chunks(payload(5000), KnstFrame.TYPE_E2EE_SIGNAL, id)[0])
        val other = KnstFrame.chunks(payload(9000), KnstFrame.TYPE_E2EE_SIGNAL, id)
        assertEquals(ChunkReassembler.Assembly.Pending, reassembler.accept(sender, other[1]))
        assertEquals(listOf(9000), dao.rows.map { it.plaintextLength })
    }
}
