package com.construct.messenger.data.local

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import uniffi.construct_core.LocalStore
import com.construct.messenger.data.local.db.ConstructDatabase
import com.construct.messenger.util.DisplayNameGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What every [ContactStore] does — Room's today and the core's `LocalStore` (TODO 136), which replaces it. A case
 * here is a promise callers lean on; an implementation that breaks one breaks them.
 */
abstract class ContactStoreContract {
    protected abstract val store: ContactStore

    private val id = "0b6e9f6a-3c1d-4f7e-9a2b-5d8c7e6f1a2b"

    @Test
    fun ensureCreatesAContactUnderTheGeneratedNameAndLeavesOneThatExists() = runTest {
        store.ensure(id)
        val created = store.get(id)!!
        assertTrue(created.isContact)
        assertEquals(DisplayNameGenerator.generate(id), created.displayName)

        store.setAlias(id, "Kostya")
        store.ensure(id)
        assertEquals("Kostya", store.get(id)!!.localAlias)
    }

    @Test
    fun aFieldWriteToNoRowCreatesNone() = runTest {
        store.setBlocked(id, true)
        store.setAlias(id, "x")
        store.setKtStatus(id, 2)
        store.setSecurityNotice(id, 1)
        store.setAccountAddress(id, ByteArray(32) { 7 })
        store.setSharingWith(id, true)
        assertNull(store.get(id))
    }

    @Test
    fun aFieldWriteChangesOnlyItsField() = runTest {
        store.upsert(ContactRecord(id = id, username = "kostya", displayName = "Konstantin", isContact = true, ktStatus = 1))
        store.setSecurityNotice(id, 3)
        store.setAccountAddress(id, ByteArray(32) { 7 })
        val row = store.get(id)!!
        assertEquals(3, row.securityNotice)
        assertArrayEquals(ByteArray(32) { 7 }, row.accountAddress)
        assertEquals("Konstantin", row.displayName)
        assertEquals("kostya", row.username)
        assertEquals(1, row.ktStatus)
    }

    @Test
    fun rememberIdentityCreatesTheRowWhenAbsentAndKeepsTheRestWhenNot() = runTest {
        store.rememberIdentity(id, byteArrayOf(1))
        assertArrayEquals(byteArrayOf(1), store.get(id)!!.identityPublic)
        assertTrue(store.get(id)!!.isContact)

        store.setAlias(id, "Kostya")
        store.rememberIdentity(id, byteArrayOf(2))
        assertArrayEquals(byteArrayOf(2), store.get(id)!!.identityPublic)
        assertEquals("Kostya", store.get(id)!!.localAlias)
    }

    @Test
    fun blockingMovesARowFromContactsToBlocked() = runTest {
        store.ensure(id)
        assertEquals(listOf(id), store.observeContacts().first().map { it.id })
        store.setBlocked(id, true)
        assertEquals(emptyList<String>(), store.observeContacts().first().map { it.id })
        assertEquals(listOf(id), store.observeBlocked().first().map { it.id })
        assertEquals(listOf(id), store.observeAll().first().map { it.id })
    }

    @Test
    fun aBlankAliasClearsItAndOneIsKeptTrimmed() = runTest {
        store.ensure(id)
        store.setAlias(id, "  Kostya ")
        assertEquals("Kostya", store.get(id)!!.localAlias)
        store.setAlias(id, " ")
        assertNull(store.get(id)!!.localAlias)
    }

    @Test
    fun sharingWithLeavesBlockedOut() = runTest {
        val other = "1c7f0a7b-4d2e-4a8f-8b3c-6e9d8f7a2b3c"
        store.ensure(id)
        store.ensure(other)
        store.setSharingWith(id, true)
        store.setSharingWith(other, true)
        store.setBlocked(other, true)
        assertEquals(listOf(id), store.sharingWith())
    }

    @Test
    fun aPendingAvatarIsClearedOrCompletedOnlyWhileItIsTheOneHeld() = runTest {
        val held = byteArrayOf(1, 2)
        store.upsert(ContactRecord(id = id, isContact = true, pendingAvatarRef = held, pendingAvatarSinceMs = 5))
        assertEquals(listOf(id), store.pendingAvatarIds())

        assertFalse(store.completePendingAvatar(id, byteArrayOf(9), byteArrayOf(3)))
        assertFalse(store.clearPendingAvatar(id, byteArrayOf(9)))
        assertArrayEquals(held, store.get(id)!!.pendingAvatarRef)

        assertTrue(store.completePendingAvatar(id, held, byteArrayOf(3)))
        val row = store.get(id)!!
        assertArrayEquals(byteArrayOf(3), row.avatarData)
        assertNull(row.pendingAvatarRef)
        assertNull(row.pendingAvatarSinceMs)
        assertFalse(store.clearPendingAvatar(id, held))
        assertEquals(emptyList<String>(), store.pendingAvatarIds())
    }

    @Test
    fun deleteRemovesTheRow() = runTest {
        store.ensure(id)
        store.delete(id)
        assertNull(store.get(id))
        assertNull(store.observe(id).first())
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RoomContactStoreTest : ContactStoreContract() {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), ConstructDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    override val store: ContactStore = RoomContactStore(db.userDao())

    @After
    fun close() = db.close()
}

class FakeContactStoreTest : ContactStoreContract() {
    override val store: ContactStore = FakeContactStore()
}

/** The same cases on the core's encrypted store — the host build of the library the app ships. */
class CoreContactStoreTest : ContactStoreContract() {
    private val core = LocalStore.inMemory(ByteArray(32) { 1 })
    override val store: ContactStore = CoreContactStore(LocalStoreFeed(core))

    @After
    fun close() = core.close()

    /** What the screens rely on: a write shows on a list already watched, once. */
    @Test
    fun aWatchedListHearsALaterWrite() = runTest {
        val id = "2d8a1b8c-5e3f-4b9a-9c4d-7f0e9a8b3c4d"
        val seen = async(Dispatchers.Default) {
            withTimeout(5_000) { store.observeContacts().take(2).toList() }
        }
        // Real time, off the test scheduler: let the watcher subscribe and read once.
        withContext(Dispatchers.Default) { delay(300) }
        store.ensure(id)
        val lists = seen.await()
        assertEquals(emptyList<String>(), lists[0].map { it.id })
        assertEquals(listOf(id), lists[1].map { it.id })
    }
}
