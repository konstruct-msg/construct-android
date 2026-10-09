package com.construct.messenger.data.local

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.construct.messenger.data.local.db.ConstructDatabase
import com.construct.messenger.data.model.DeliveryStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What every [MessageStore] does — Room's today and the core's `LocalStore` (TODO 136), which
 * replaces it. The chat and its contact are set up first, as the core requires.
 */
abstract class MessageStoreContract {
    protected abstract val contacts: ContactStore
    protected abstract val chats: ChatStore
    protected abstract val store: MessageStore

    private val peer = "0b6e9f6a-3c1d-4f7e-9a2b-5d8c7e6f1a2b"
    private val chat = "chat-0b6e9f6a"

    @Before
    fun openChat() = runTest {
        contacts.ensure(peer)
        chats.ensure(chat, peer)
    }

    private fun message(id: String, at: Long, status: DeliveryStatus = DeliveryStatus.SENT, text: String = "m-$id", contentType: Int = 0) =
        MessageRecord(id, chat, text, isSentByMe = true, timestampMs = at, deliveryStatus = status, contentType = contentType)

    @Test
    fun insertKeepsTheMessageThatIsThere() = runTest {
        assertTrue(store.insert(message("a", 1, DeliveryStatus.DELIVERED, text = "first")))
        assertFalse(store.insert(message("a", 1, DeliveryStatus.SENT, text = "again")))
        val row = store.get("a")!!
        assertEquals("first", row.text)
        assertEquals(DeliveryStatus.DELIVERED, row.deliveryStatus)
    }

    /** iOS compares ids `==[c]`; a write lands on the stored id. */
    @Test
    fun idsAreFoundWithoutCase() = runTest {
        store.insert(message("AbC-1", 1, DeliveryStatus.SENDING))
        assertEquals("AbC-1", store.get("abc-1")!!.id)
        store.setDeliveryStatus("ABC-1", DeliveryStatus.SENT)
        assertEquals(DeliveryStatus.SENT, store.get("AbC-1")!!.deliveryStatus)
        store.delete("abc-1")
        assertNull(store.get("AbC-1"))
    }

    /** The core's rule: a weaker fact written after a stronger one is refused. */
    @Test
    fun aDeliveryStatusIsNotLowered() = runTest {
        store.insert(message("a", 1, DeliveryStatus.SENDING))
        assertTrue(store.setDeliveryStatus("a", DeliveryStatus.DELIVERED))
        assertFalse("the receipt came first", store.setDeliveryStatus("a", DeliveryStatus.SENT))
        assertFalse(store.setDeliveryStatus("a", DeliveryStatus.FAILED))
        assertEquals(DeliveryStatus.DELIVERED, store.get("a")!!.deliveryStatus)

        store.insert(message("b", 2, DeliveryStatus.SENT))
        assertFalse(store.setDeliveryStatus("b", DeliveryStatus.FAILED))
        assertFalse("unchanged", store.setDeliveryStatus("b", DeliveryStatus.SENT))
    }

    /** Retry: a failed send goes back to sending — the same rank. */
    @Test
    fun aFailedSendCanBeTriedAgain() = runTest {
        store.insert(message("a", 1, DeliveryStatus.FAILED))
        assertTrue(store.setDeliveryStatus("a", DeliveryStatus.SENDING))
        assertTrue(store.setDeliveryStatus("a", DeliveryStatus.FAILED))
        assertTrue(store.setDeliveryStatus("a", DeliveryStatus.SENT))
    }

    @Test
    fun anEditMarksItAndKeepsTheMediaUnlessGiven() = runTest {
        store.insert(message("a", 1).copy(mediaType = "album", mediaPayload = byteArrayOf(1)))
        store.edit("a", "caption", null)
        assertEquals("caption", store.get("a")!!.text)
        assertTrue(store.get("a")!!.isEdited)
        assertArrayEquals(byteArrayOf(1), store.get("a")!!.mediaPayload)

        store.edit("a", "caption 2", byteArrayOf(2))
        assertArrayEquals(byteArrayOf(2), store.get("a")!!.mediaPayload)

        store.setMedia("a", "voice", byteArrayOf(3))
        assertEquals("voice", store.get("a")!!.mediaType)
        assertArrayEquals(byteArrayOf(3), store.get("a")!!.mediaPayload)
    }

    @Test
    fun theTranscriptIsUserVisibleMessagesOldestFirst() = runTest {
        store.insert(message("late", 30))
        store.insert(message("early", 10))
        store.insert(message("control", 20, contentType = 25))
        assertEquals(listOf("early", "late"), store.observeChat(chat).first().map { it.id })
        assertEquals("late", store.latestVisible(chat)!!.id)
    }

    @Test
    fun deleteChatTakesItsMessages() = runTest {
        store.insert(message("a", 1))
        store.insert(message("b", 2))
        store.deleteChat(chat)
        assertNull(store.get("a"))
        assertNull(store.latestVisible(chat))
    }

    @Test
    fun aWriteToNoMessageCreatesNone() = runTest {
        assertFalse(store.setDeliveryStatus("x", DeliveryStatus.SENT))
        store.edit("x", "t", null)
        store.setMedia("x", "album", byteArrayOf(1))
        assertNull(store.get("x"))
    }

    @Test
    fun refreshChatPreviewPointsAtWhatIsLeft() = runTest {
        store.insert(message("a", 10, text = "older"))
        store.insert(message("b", 20, text = "newer"))
        chats.noteMessage(chat, peer, "newer", 20, unread = false)
        store.delete("b")
        store.refreshChatPreview(chats, chat)
        assertEquals("older", chats.get(chat)!!.lastMessageText)
        store.delete("a")
        store.refreshChatPreview(chats, chat)
        assertNull(chats.get(chat)!!.lastMessageText)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RoomMessageStoreTest : MessageStoreContract() {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), ConstructDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    override val contacts: ContactStore = RoomContactStore(db.userDao())
    override val chats: ChatStore = RoomChatStore(db.chatDao())
    override val store: MessageStore = RoomMessageStore(db.messageDao())

    @After
    fun close() = db.close()
}

class FakeMessageStoreTest : MessageStoreContract() {
    override val contacts: ContactStore = FakeContactStore()
    override val chats: ChatStore = FakeChatStore()
    override val store: MessageStore = FakeMessageStore()
}
