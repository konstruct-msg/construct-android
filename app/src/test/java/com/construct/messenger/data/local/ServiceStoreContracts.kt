package com.construct.messenger.data.local

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.construct.messenger.data.local.db.ConstructDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uniffi.construct_core.LocalStore

/** What every [ServerMessageIdStore] does — Room's today, the core's next (TODO 136). */
abstract class ServerMessageIdStoreContract {
    protected abstract val store: ServerMessageIdStore

    @Test
    fun aServerIdNamesItsMessage() = runTest {
        store.record("srv-1", "m1", 10)
        assertEquals("m1", store.localId("srv-1"))
        assertNull(store.localId("srv-2"))
    }

    @Test
    fun aServerIdRecordedAgainNamesTheLatestMessage() = runTest {
        store.record("srv-1", "m1", 10)
        store.record("srv-1", "m2", 20)
        assertEquals("m2", store.localId("srv-1"))
    }

    /** Before the cutoff, not at it. */
    @Test
    fun pairsRecordedBeforeTheCutoffAreForgotten() = runTest {
        store.record("old", "m1", 99)
        store.record("edge", "m2", 100)
        store.forgetBefore(100)
        assertNull(store.localId("old"))
        assertEquals("m2", store.localId("edge"))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RoomServerMessageIdStoreTest : ServerMessageIdStoreContract() {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), ConstructDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    override val store: ServerMessageIdStore = RoomServerMessageIdStore(db.serverMessageIdDao())

    @After
    fun close() = db.close()
}

class FakeServerMessageIdStoreTest : ServerMessageIdStoreContract() {
    override val store: ServerMessageIdStore = FakeServerMessageIdStore()
}

class CoreServerMessageIdStoreTest : ServerMessageIdStoreContract() {
    private val core = LocalStore.inMemory(ByteArray(32) { 1 })
    override val store: ServerMessageIdStore = CoreServerMessageIdStore(core)

    @After
    fun close() = core.close()
}

/** What every [PeerDeviceStore] does — Room's today, the core's next (TODO 136). */
abstract class PeerDeviceStoreContract {
    protected abstract val store: PeerDeviceStore

    private val ann = "0b6e9f6a-3c1d-4f7e-9a2b-5d8c7e6f1a2b"
    private val bob = "1c7f0a7b-4d2e-4a8f-8b3c-6e9d8f7a2b3c"

    private fun device(id: Char, account: String = ann, at: Long = 10) =
        PeerDeviceRecord(id.toString().repeat(32), account, ByteArray(32) { id.code.toByte() }, at)

    @Test
    fun anAccountsDevicesAreOldestFirstThenById() = runTest {
        store.record(device('c', at = 20))
        store.record(device('b', at = 10))
        store.record(device('a', at = 10))
        store.record(device('d', account = bob))
        assertEquals(listOf(device('a'), device('b'), device('c', at = 20)), store.forAccount(ann))
        assertEquals(device('d', account = bob), store.forDevice("d".repeat(32)))
    }

    /** A known device keeps its first row — under its first account, whoever names it again. */
    @Test
    fun aKnownDeviceKeepsItsFirstRow() = runTest {
        assertTrue(store.record(device('a', at = 10)))
        assertFalse(store.record(device('a', at = 99)))
        assertFalse(store.record(device('a', account = bob)))
        assertEquals(device('a', at = 10), store.forDevice("a".repeat(32)))
        assertTrue(store.forAccount(bob).isEmpty())
    }

    @Test
    fun retainForgetsTheAccountsOtherDevicesOnly() = runTest {
        store.record(device('a'))
        store.record(device('b'))
        store.record(device('d', account = bob))
        store.retain(ann, emptyList())
        assertEquals(2, store.forAccount(ann).size)
        store.retain(ann, listOf("a".repeat(32)))
        assertEquals(listOf(device('a')), store.forAccount(ann))
        assertEquals(1, store.forAccount(bob).size)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RoomPeerDeviceStoreTest : PeerDeviceStoreContract() {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), ConstructDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    override val store: PeerDeviceStore = RoomPeerDeviceStore(db.peerDeviceDao())

    @After
    fun close() = db.close()
}

class FakePeerDeviceStoreTest : PeerDeviceStoreContract() {
    override val store: PeerDeviceStore = FakePeerDeviceStore()
}

class CorePeerDeviceStoreTest : PeerDeviceStoreContract() {
    private val core = LocalStore.inMemory(ByteArray(32) { 1 })
    override val store: PeerDeviceStore = CorePeerDeviceStore(core)

    @After
    fun close() = core.close()
}
