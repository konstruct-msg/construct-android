package com.construct.messenger.data.local

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.construct.messenger.data.local.db.ConstructDatabase
import uniffi.construct_core.LocalStore
import com.construct.messenger.data.model.DeliveryStatus
import com.construct.messenger.util.MediaWire
import com.construct.messenger.util.ServerMessageOrder
import com.google.protobuf.ByteString
import shared.proto.messaging.v1.Content.MediaAlbumMessage
import shared.proto.messaging.v1.Content.MediaMessage
import shared.proto.messaging.v1.Content.MediaType
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

    private fun album(caption: String, mediaId: String = "store-1"): ByteArray = MediaAlbumMessage.newBuilder()
        .addItems(MediaMessage.newBuilder().setMediaType(MediaType.MEDIA_TYPE_IMAGE).setFileUrl(mediaId).setEncryptionKey(ByteString.copyFrom(ByteArray(32) { 7 })))
        .setCaption(caption)
        .build()
        .toByteArray()

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

    /** iOS compares ids `==[c]`; the core stores them lowercase. Either way a write finds the row. */
    @Test
    fun idsAreFoundWithoutCase() = runTest {
        store.insert(message("AbC-1", 1, DeliveryStatus.SENDING))
        assertTrue(store.get("abc-1")!!.id.equals("AbC-1", ignoreCase = true))
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
    fun anEditOfTextMarksIt() = runTest {
        store.insert(message("t", 1, text = "helo"))
        store.applyEdit(store.get("t")!!, "hello")
        assertEquals("hello", store.get("t")!!.text)
        assertTrue(store.get("t")!!.isEdited)
    }

    /** A photo's caption edit rewrites the album it is sent from again (`applyEdit`). */
    @Test
    fun aCaptionEditRewritesTheAlbum() = runTest {
        store.insert(message("a", 1, text = "look").copy(mediaType = MediaWire.KIND_ALBUM, mediaPayload = album("look")))
        store.applyEdit(store.get("a")!!, "look at this")
        val row = store.get("a")!!
        assertEquals("look at this", row.text)
        assertTrue(row.isEdited)
        assertArrayEquals(album("look at this"), row.mediaPayload)
    }

    /** The uploaded media in place of the staged copy: the same message, not an edit. */
    @Test
    fun setMediaIsNotAnEdit() = runTest {
        store.insert(message("a", 1, text = "look").copy(mediaType = MediaWire.KIND_ALBUM, mediaPayload = album("look", "local-1")))
        store.setMedia("a", MediaWire.KIND_ALBUM, album("look", "store-1"))
        val row = store.get("a")!!
        assertArrayEquals(album("look", "store-1"), row.mediaPayload)
        assertEquals("look", row.text)
        assertFalse(row.isEdited)
    }

    /** A quote and its kind come back as they went in. */
    @Test
    fun aReplyRoundTrips() = runTest {
        store.insert(message("r", 1, text = "yes").copy(replyToId = "q-1", replyPreview = "the photo", replyMediaType = "MEDIA_TYPE_IMAGE"))
        val row = store.get("r")!!
        assertEquals("q-1", row.replyToId)
        assertEquals("the photo", row.replyPreview)
        assertEquals("MEDIA_TYPE_IMAGE", row.replyMediaType)
    }

    @Test
    fun theTranscriptIsUserVisibleMessagesOldestFirst() = runTest {
        store.insert(message("late", 30))
        store.insert(message("early", 10))
        store.insert(message("control", 20, contentType = 25))
        assertEquals(listOf("early", "late"), store.observeChat(chat).first().map { it.id })
        assertEquals("late", store.latestVisible(chat)!!.id)
    }

    /** The server's order, not the sender's clock; a key of none puts a message at its own time. */
    @Test
    fun theTranscriptFollowsTheServersOrder() = runTest {
        store.insert(message("late-clock", 99).copy(orderKey = ServerMessageOrder.key(1_000, 1)!!))
        store.insert(message("early-clock", 5).copy(orderKey = ServerMessageOrder.key(2_000, 0)!!))
        store.insert(message("mine", 50).copy(orderKey = ServerMessageOrder.pending("mine")))
        assertEquals(listOf("late-clock", "early-clock", "mine"), store.observeChat(chat).first().map { it.id })
        assertEquals("mine", store.latestVisible(chat)!!.id)

        // Acknowledged: out of the pending place, into the server's.
        assertTrue(store.setOrderKey("MINE", ServerMessageOrder.key(1_500, 0)!!))
        assertEquals(listOf("late-clock", "mine", "early-clock"), store.observeChat(chat).first().map { it.id })
        assertFalse("unchanged", store.setOrderKey("mine", ServerMessageOrder.key(1_500, 0)!!))
        assertFalse(store.setOrderKey("nobody", ServerMessageOrder.key(1, 0)!!))

        store.insert(message("local", 1_200))
        assertEquals(ServerMessageOrder.local(1_200, "local"), store.get("local")!!.orderKey)
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

/** The same cases on the core's encrypted store — the host build of the library the app ships. */
class CoreMessageStoreTest : MessageStoreContract() {
    private val core = LocalStore.inMemory(ByteArray(32) { 1 })
    private val feed = LocalStoreFeed(core)
    override val contacts: ContactStore = CoreContactStore(feed)
    override val chats: ChatStore = CoreChatStore(feed)
    override val store: MessageStore = CoreMessageStore(feed, myUserId = { "me" })

    @After
    fun close() = core.close()
}
