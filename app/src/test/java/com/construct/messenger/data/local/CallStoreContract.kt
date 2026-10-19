package com.construct.messenger.data.local

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.construct.messenger.calls.CallRecordStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uniffi.construct_core.LocalStore

/**
 * What every [CallStore] does — on the core's `LocalStore` and the fake (TODO 136).
 * A call names its peer by id and keeps the name it had: no contact is required.
 */
abstract class CallStoreContract {
    protected abstract val store: CallStore

    private fun call(id: String, at: Long, status: CallRecordStatus = CallRecordStatus.COMPLETED, incoming: Boolean = false) =
        CallRecord(id, "0b6e9f6a-3c1d-4f7e-9a2b-5d8c7e6f1a2b", "Ann", incoming, status, at, at + 5_000, 5)

    private suspend fun ids() = store.observeRecent().first().map { it.id }

    @Test
    fun theNewestCallComesFirst() = runTest {
        store.put(call("a", 10))
        store.put(call("c", 30))
        store.put(call("b", 20))
        assertEquals(listOf("c", "b", "a"), ids())
    }

    @Test
    fun aCallIsReadBackAsWritten() = runTest {
        CallRecordStatus.entries.forEachIndexed { i, status -> store.put(call("s$i", i.toLong(), status, incoming = i % 2 == 0)) }
        val rows = store.observeRecent().first().associateBy { it.id }
        CallRecordStatus.entries.forEachIndexed { i, status ->
            assertEquals(call("s$i", i.toLong(), status, incoming = i % 2 == 0), rows["s$i"])
        }
    }

    @Test
    fun theSameCallIdIsOneRow() = runTest {
        store.put(call("a", 10, CallRecordStatus.MISSED))
        store.put(call("a", 10, CallRecordStatus.DECLINED))
        assertEquals(listOf(CallRecordStatus.DECLINED), store.observeRecent().first().map { it.status })
    }

    @Test
    fun theListIsTheNewest200() = runTest {
        repeat(CallStore.RECENT + 1) { store.put(call("c$it", it.toLong())) }
        val ids = ids()
        assertEquals(CallStore.RECENT, ids.size)
        assertEquals("c${CallStore.RECENT}", ids.first())
        assertTrue("c0" !in ids)
    }

    @Test
    fun oneCallOrAllAreDeleted() = runTest {
        store.put(call("a", 10))
        store.put(call("b", 20))
        store.delete("a")
        assertEquals(listOf("b"), ids())
        store.deleteAll()
        assertTrue(ids().isEmpty())
    }
}

class FakeCallStoreTest : CallStoreContract() {
    override val store: CallStore = FakeCallStore()
}

/** The same cases on the core's encrypted store — the host build of the library the app ships. */
class CoreCallStoreTest : CallStoreContract() {
    private val core = LocalStore.inMemory(ByteArray(32) { 1 })
    override val store: CallStore = CoreCallStore(LocalStoreFeed(core))

    /** iOS's numbers, so a row reads the same whichever client wrote it. */
    @Test
    fun theRowHoldsIosNumbers() = runTest {
        store.put(CallRecord("a", "p", "Ann", incoming = true, CallRecordStatus.DECLINED, 10, 15_010, 15))
        val row = core.calls(1u).single()
        assertEquals(1, row.direction.toInt())
        assertEquals(2, row.status.toInt())
    }

    @After
    fun close() = core.close()
}
