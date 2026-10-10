package com.construct.messenger.data.local

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.construct.messenger.data.local.db.ConstructDatabase
import uniffi.construct_core.LocalStore
import kotlinx.coroutines.flow.first
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

/**
 * What every [ChatStore] does — Room's today and the core's `LocalStore` (TODO 136), which
 * replaces it. Each case sets up the peer's contact first, as the core requires.
 */
abstract class ChatStoreContract {
    protected abstract val contacts: ContactStore
    protected abstract val store: ChatStore

    private val peer = "0b6e9f6a-3c1d-4f7e-9a2b-5d8c7e6f1a2b"
    private val chat = "chat-0b6e9f6a"

    private suspend fun open(preview: String = "hi", at: Long = 10, unread: Boolean = false) {
        contacts.ensure(peer)
        store.noteMessage(chat, peer, preview, at, unread)
    }

    @Test
    fun theFirstMessageCreatesTheChatWithItsPreview() = runTest {
        open(preview = "hi", at = 10, unread = true)
        val row = store.get(chat)!!
        assertEquals(peer, row.peerId)
        assertEquals("hi", row.lastMessageText)
        assertEquals(10L, row.lastMessageTimeMs)
        assertEquals(1, row.unreadCount)
        assertFalse(row.isPinned)
    }

    @Test
    fun insertLeavesAChatThatIsThere() = runTest {
        open(preview = "hi", at = 10)
        assertFalse(store.insert(ChatRecord(chat, peer, "other", 99, unreadCount = 5)))
        assertEquals("hi", store.get(chat)!!.lastMessageText)
        assertEquals(0, store.get(chat)!!.unreadCount)
    }

    /** Messages arrive out of order: an older one must not replace a newer preview. */
    @Test
    fun thePreviewMovesOnlyForward() = runTest {
        open(preview = "new", at = 20)
        store.noteMessage(chat, peer, "old", 10, unread = true)
        assertEquals("new", store.get(chat)!!.lastMessageText)
        assertEquals(1, store.get(chat)!!.unreadCount)

        store.advancePreview(chat, "edited", 20)
        assertEquals("edited", store.get(chat)!!.lastMessageText)
        store.advancePreview(chat, "newer", 30)
        assertEquals(30L, store.get(chat)!!.lastMessageTimeMs)
    }

    /** After a deletion the preview goes back to what is left — or to nothing. */
    @Test
    fun setPreviewMovesItBackOrClearsIt() = runTest {
        open(preview = "new", at = 20)
        store.setPreview(chat, "old", 10)
        assertEquals("old", store.get(chat)!!.lastMessageText)
        store.setPreview(chat, null, null)
        assertNull(store.get(chat)!!.lastMessageText)
        assertNull(store.get(chat)!!.lastMessageTimeMs)
    }

    @Test
    fun unreadCountsAndResets() = runTest {
        open(unread = true)
        store.incrementUnread(chat)
        assertEquals(2, store.get(chat)!!.unreadCount)
        store.setUnread(chat, 0)
        assertEquals(0, store.get(chat)!!.unreadCount)
    }

    @Test
    fun pinnedChatsListFirstThenTheMostRecent() = runTest {
        val other = "1c7f0a7b-4d2e-4a8f-8b3c-6e9d8f7a2b3c"
        open(at = 10)
        contacts.ensure(other)
        store.noteMessage("chat-other", other, "later", 20, unread = false)
        assertEquals(listOf("chat-other", chat), store.observeAll().first().map { it.id })
        store.setPinned(chat, true)
        assertEquals(listOf(chat, "chat-other"), store.observeAll().first().map { it.id })
    }

    @Test
    fun aWriteToNoChatCreatesNone() = runTest {
        store.advancePreview(chat, "x", 1)
        store.setPreview(chat, "x", 1)
        store.incrementUnread(chat)
        store.setUnread(chat, 3)
        store.setPinned(chat, true)
        assertNull(store.get(chat))
    }

    @Test
    fun deleteRemovesTheChat() = runTest {
        open()
        store.delete(chat)
        assertNull(store.get(chat))
        assertTrue(store.observeAll().first().isEmpty())
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RoomChatStoreTest : ChatStoreContract() {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), ConstructDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    override val contacts: ContactStore = RoomContactStore(db.userDao())
    override val store: ChatStore = RoomChatStore(db.chatDao())

    @After
    fun close() = db.close()
}

class FakeChatStoreTest : ChatStoreContract() {
    override val contacts: ContactStore = FakeContactStore()
    override val store: ChatStore = FakeChatStore()
}

/** The same cases on the core's encrypted store — the host build of the library the app ships. */
class CoreChatStoreTest : ChatStoreContract() {
    private val core = LocalStore.inMemory(ByteArray(32) { 1 })
    private val feed = LocalStoreFeed(core)
    override val contacts: ContactStore = CoreContactStore(feed)
    override val store: ChatStore = CoreChatStore(feed)

    @After
    fun close() = core.close()
}
