package com.construct.messenger.data.local

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uniffi.construct_core.LocalStore

/** What every [ProcessedStore] does — on the core's store and the fakes (TODO 136). */
abstract class ProcessedStoreContract {
    protected abstract val store: ProcessedStore

    @Test
    fun aRecordedIdIsHeld() = runTest {
        store.record("m1", "sender-1", 10)
        store.record("m2", "sender-1", 10)
        assertEquals(setOf("m1", "m2"), store.ids().toSet())
    }

    /** A redelivery does not make the id younger: it ages out from when it was first processed. */
    @Test
    fun aSecondRecordKeepsTheFirstTime() = runTest {
        store.record("m1", "sender-1", 10)
        store.record("m1", "sender-1", 200)
        assertEquals(1, store.forgetBefore(100))
        assertTrue(store.ids().isEmpty())
    }

    /** Before the cutoff, not at it. */
    @Test
    fun idsProcessedBeforeTheCutoffAreForgotten() = runTest {
        store.record("old", "sender-1", 99)
        store.record("edge", "sender-1", 100)
        assertEquals(1, store.forgetBefore(100))
        assertEquals(listOf("edge"), store.ids())
    }
}

class FakeProcessedStoreTest : ProcessedStoreContract() {
    override val store: ProcessedStore = FakeProcessedStore()
}

class CoreProcessedStoreTest : ProcessedStoreContract() {
    private val core = LocalStore.inMemory(ByteArray(32) { 1 })
    override val store: ProcessedStore = CoreProcessedStore(core)

    @After
    fun close() = core.close()
}

/** What every [PendingChunkStore] does — on the core's store and the fakes (TODO 136). */
abstract class PendingChunkStoreContract {
    protected abstract val store: PendingChunkStore

    private val ann = "0b6e9f6a-3c1d-4f7e-9a2b-5d8c7e6f1a2b"
    private val bob = "1c7f0a7b-4d2e-4a8f-8b3c-6e9d8f7a2b3c"

    private fun chunk(index: Int, sender: String = ann, message: String = "msg-1", byte: Byte = index.toByte(), at: Long = 10) =
        PendingChunk(sender, message, index, totalChunks = 3, plaintextLength = 9000, contentType = 200, payload = ByteArray(4) { byte }, receivedAtMs = at)

    @Test
    fun chunksComeBackByIndexWithEveryField() = runTest {
        store.add(chunk(2))
        store.add(chunk(0))
        val held = store.chunks(ann, "msg-1")
        assertEquals(listOf(0, 2), held.map { it.chunkIndex })
        with(held.first()) {
            assertEquals(ann, senderId)
            assertEquals("msg-1", messageId)
            assertEquals(3, totalChunks)
            assertEquals(9000, plaintextLength)
            assertEquals(200, contentType)
            assertArrayEquals(ByteArray(4) { 0 }, payload)
            assertEquals(10L, receivedAtMs)
        }
    }

    /** Its ratchet key is spent: the first decryption is the one that counts. */
    @Test
    fun aRedeliveredChunkKeepsTheCopyHeld() = runTest {
        store.add(chunk(0, byte = 1))
        store.add(chunk(0, byte = 9, at = 50))
        val held = store.chunks(ann, "msg-1").single()
        assertArrayEquals(ByteArray(4) { 1 }, held.payload)
        assertEquals(10L, held.receivedAtMs)
    }

    /** One sender's frames never fill in another's message. */
    @Test
    fun chunksAreKeptPerSender() = runTest {
        store.add(chunk(0, sender = ann))
        store.add(chunk(1, sender = bob))
        assertEquals(listOf(0), store.chunks(ann, "msg-1").map { it.chunkIndex })
        assertEquals(listOf(1), store.chunks(bob, "msg-1").map { it.chunkIndex })
    }

    @Test
    fun deleteDropsOneMessage() = runTest {
        store.add(chunk(0))
        store.add(chunk(0, message = "msg-2"))
        store.delete(ann, "msg-1")
        assertTrue(store.chunks(ann, "msg-1").isEmpty())
        assertEquals(1, store.chunks(ann, "msg-2").size)
    }

    /** Before the cutoff, not at it. */
    @Test
    fun chunksReceivedBeforeTheCutoffAreForgotten() = runTest {
        store.add(chunk(0, at = 99))
        store.add(chunk(1, at = 100))
        assertEquals(1, store.forgetBefore(100))
        assertEquals(listOf(1), store.chunks(ann, "msg-1").map { it.chunkIndex })
    }
}

class FakePendingChunkStoreTest : PendingChunkStoreContract() {
    override val store: PendingChunkStore = FakePendingChunkStore()
}

class CorePendingChunkStoreTest : PendingChunkStoreContract() {
    private val core = LocalStore.inMemory(ByteArray(32) { 1 })
    override val store: PendingChunkStore = CorePendingChunkStore(core)

    @After
    fun close() = core.close()
}

/** What every [PendingResendStore] does — on the core's store and the fakes (TODO 136). */
abstract class PendingResendStoreContract {
    protected abstract val store: PendingResendStore

    private fun resend(message: String, device: String = "dev-1", at: Long = 10, attempts: Int = 0) =
        PendingResend(message, device, "acct-1", at, attempts)

    @Test
    fun requestsComeBackOldestFirst() = runTest {
        store.queue(resend("m2", at = 20))
        store.queue(resend("m1", at = 10))
        assertEquals(listOf(resend("m1", at = 10), resend("m2", at = 20)), store.all())
    }

    /** A second error for the same copy keeps the first row, its age and its attempts. */
    @Test
    fun aSecondRequestKeepsTheFirstRow() = runTest {
        store.queue(resend("m1", at = 10, attempts = 2))
        store.queue(resend("m1", at = 50))
        assertEquals(listOf(resend("m1", at = 10, attempts = 2)), store.all())
    }

    @Test
    fun theSameMessageToAnotherDeviceIsItsOwnRequest() = runTest {
        store.queue(resend("m1", device = "dev-1"))
        store.queue(resend("m1", device = "dev-2"))
        assertEquals(setOf("dev-1", "dev-2"), store.all().map { it.deviceId }.toSet())
    }

    @Test
    fun anAttemptIsCountedOnItsRowOnly() = runTest {
        store.queue(resend("m1"))
        store.countAttempt("m1", "dev-1")
        store.countAttempt("m1", "dev-1")
        store.countAttempt("m9", "dev-1")
        assertEquals(listOf(resend("m1", attempts = 2)), store.all())
    }

    @Test
    fun deleteDropsOneRequest() = runTest {
        store.queue(resend("m1"))
        store.queue(resend("m2"))
        store.delete("m1", "dev-1")
        assertEquals(listOf("m2"), store.all().map { it.messageId })
    }

    /** Before the cutoff, not at it. */
    @Test
    fun requestsMadeBeforeTheCutoffAreForgotten() = runTest {
        store.queue(resend("old", at = 99))
        store.queue(resend("edge", at = 100))
        assertEquals(1, store.forgetBefore(100))
        assertEquals(listOf("edge"), store.all().map { it.messageId })
    }
}

class FakePendingResendStoreTest : PendingResendStoreContract() {
    override val store: PendingResendStore = FakePendingResendStore()
}

class CorePendingResendStoreTest : PendingResendStoreContract() {
    private val core = LocalStore.inMemory(ByteArray(32) { 1 })
    override val store: PendingResendStore = CorePendingResendStore(core)

    @After
    fun close() = core.close()
}
