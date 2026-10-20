package com.construct.messenger.data.local

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.construct.messenger.data.model.DeliveryStatus
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
 * What every [ChatStore] does — on the core's `LocalStore` and the fake (TODO 136). Each case sets up the peer's contact first, as the core requires.
 */
abstract class ChatStoreContract {
    protected abstract val contacts: ContactStore
    protected abstract val store: ChatStore

    private val peer = "0b6e9f6a-3c1d-4f7e-9a2b-5d8c7e6f1a2b"
    private val chat = "chat-0b6e9f6a"

    private suspend fun open(preview: String = "hi", at: Long = 10, unread: Boolean = false) {
        contacts.ensure(peer)
        store.insert(ChatRecord(chat, peer, preview, at, unreadCount = if (unread) 1 else 0))
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
        store.insert(ChatRecord("chat-other", other, "later", 20))
        assertEquals(listOf("chat-other", chat), store.observeAll().first().map { it.id })
        store.setPinned(chat, true)
        assertEquals(listOf(chat, "chat-other"), store.observeAll().first().map { it.id })
    }

    @Test
    fun aWriteToNoChatCreatesNone() = runTest {
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

    /** Synapses ranks contacts by how much was said: the core's per-chat count, not the store's total. */
    @Test
    fun activityCountsEachChatsMessages() = runTest {
        val peer = "0b6e9f6a-3c1d-4f7e-9a2b-5d8c7e6f1a2b"
        val other = "1c7f0a7b-4d2e-4a8f-8b3c-6e9d8f7a2b3c"
        val messages = CoreMessageStore(feed, myUserId = { "me" })
        for ((p, chat, n) in listOf(Triple(peer, "chat-a", 2), Triple(other, "chat-b", 1))) {
            contacts.ensure(p)
            store.ensure(chat, p)
            repeat(n) { messages.insert(MessageRecord("$chat-$it", chat, "m", isSentByMe = true, timestampMs = 1, deliveryStatus = DeliveryStatus.SENT)) }
        }
        assertEquals(mapOf(peer to 2, other to 1), store.observeActivity().first().associate { it.contactId to it.messages })
    }

    /** A message written while the activity is watched is counted. Mutation: `watch(MESSAGES) { }` — reddens. */
    @Test
    fun activityFollowsALaterMessage() = runTest {
        val peer = "0b6e9f6a-3c1d-4f7e-9a2b-5d8c7e6f1a2b"
        val messages = CoreMessageStore(feed, myUserId = { "me" })
        contacts.ensure(peer)
        store.ensure("chat-a", peer)
        val seen = nextValues(store.observeActivity())
        assertEquals(0, seen.next().single().messages)
        messages.insert(MessageRecord("chat-a-0", "chat-a", "m", isSentByMe = true, timestampMs = 1, deliveryStatus = DeliveryStatus.SENT))
        assertEquals(1, seen.next().single().messages)
        seen.close()
    }

    @After
    fun close() = core.close()
}
