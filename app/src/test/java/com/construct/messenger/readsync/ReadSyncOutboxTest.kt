package com.construct.messenger.readsync

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Markers go in one message per window, or at once on leaving; never one per read (TODO 153). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ReadSyncOutboxTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val kim = "kim"
    private val alex = "alex"

    /** Mutation: send on each read — reddens. */
    @Test
    fun `reading is sent once for everything pending, at the end of the window`() = runTest {
        val sent = mutableListOf<ByteArray>()
        val outbox = ReadSyncOutbox(ReadBoundaries(context), { sent += it; true }, this, { 120_000 })
        outbox.noteRead(kim, ReadSync.Boundary.of(1, "a"))
        outbox.noteRead(alex, ReadSync.Boundary.of(2, "b"))
        advanceTimeBy(119_000)
        assertTrue(sent.isEmpty())
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(1, sent.size)
        assertEquals(setOf(kim, alex), ReadSync.decode(sent.single())!!.map { it.first }.toSet())
    }

    @Test
    fun `leaving sends at once, and nothing new sends nothing`() = runTest {
        val sent = mutableListOf<ByteArray>()
        val outbox = ReadSyncOutbox(ReadBoundaries(context), { sent += it; true }, this, { 120_000 })
        outbox.noteRead(kim, ReadSync.Boundary.of(5, "a"))
        outbox.flush()
        assertEquals(1, sent.size)
        outbox.noteRead(kim, ReadSync.Boundary.of(5, "a"))
        outbox.noteRead(kim, ReadSync.Boundary.of(4, "z"))
        outbox.flush()
        advanceTimeBy(200_000)
        assertEquals(1, sent.size)
    }

    /** A send that did not get through comes back in the next window (iOS #235). Mutation: drop it — reddens. */
    @Test
    fun `markers that did not go are sent again, a later boundary winning`() = runTest {
        val sent = mutableListOf<ByteArray>()
        var up = false
        val outbox = ReadSyncOutbox(ReadBoundaries(context), { sent += it; up }, this, { 120_000 })
        outbox.noteRead(kim, ReadSync.Boundary.of(5, "a"))
        outbox.noteRead(alex, ReadSync.Boundary.of(5, "c"))
        outbox.flush()
        assertEquals(1, sent.size)
        up = true
        outbox.noteRead(kim, ReadSync.Boundary.of(6, "b"))
        advanceTimeBy(121_000)
        runCurrent()
        assertEquals(2, sent.size)
        assertEquals(
            "alex's marker came back; kim's newer one replaced the lost one",
            mapOf(alex to ReadSync.Boundary.of(5, "c"), kim to ReadSync.Boundary.of(6, "b")),
            ReadSync.decode(sent.last())!!.toMap(),
        )
    }

}
