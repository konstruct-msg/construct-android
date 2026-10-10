package com.construct.messenger.data.local

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uniffi.construct_core.LocalStore
import com.construct.messenger.data.model.DeliveryStatus

/** The quote as iOS `ReplyPreviewPayload.storedContent` keeps it, and a whole row as iOS reads it. */
class ReplyQuoteStorageTest {
    @Test
    fun aTextQuoteIsItsText() {
        assertEquals("are you there?", ReplyQuoteStorage.encode("are you there?", null))
        assertNull(ReplyQuoteStorage.encode("", null))
        assertEquals(Triple("plain", null, false), ReplyQuoteStorage.decode("plain"))
    }

    @Test
    fun aMediaQuoteIsIosEnvelope() {
        val stored = ReplyQuoteStorage.encode("the photo", "MEDIA_TYPE_IMAGE")!!
        assertEquals("""{"type":"reply_preview","kind":"image","text":"the photo"}""", stored)
        assertEquals("""{"type":"reply_preview","kind":"audio"}""", ReplyQuoteStorage.encode(null, "MEDIA_TYPE_AUDIO"))
        assertEquals(Triple("the photo", "MEDIA_TYPE_IMAGE", false), ReplyQuoteStorage.decode(stored))
    }

    /** What iOS writes for a video note — a kind Android's wire never names. */
    @Test
    fun iosVideoNoteKindIsRead() {
        assertEquals(Triple("", "MEDIA_TYPE_VIDEO", true), ReplyQuoteStorage.decode("""{"type":"reply_preview","kind":"videoNote"}"""))
        assertEquals("""{"type":"reply_preview","kind":"videoNote"}""", ReplyQuoteStorage.encode(null, "MEDIA_TYPE_VIDEO", videoNote = true))
    }

    private val core = LocalStore.inMemory(ByteArray(32) { 2 })

    @After
    fun close() = core.close()

    /** The raw row in the core: what iOS will find there. */
    @Test
    fun aRowInTheCoreIsIosShaped() = runTest {
        val feed = LocalStoreFeed(core)
        CoreContactStore(feed).ensure("peer")
        CoreChatStore(feed).insert(ChatRecord("chat", "peer"))
        CoreMessageStore(feed, myUserId = { "me" }).insert(
            MessageRecord("M-1", "chat", "yes", isSentByMe = true, timestampMs = 5, deliveryStatus = DeliveryStatus.DELIVERED,
                replyToId = "q", replyPreview = "the photo", replyMediaType = "MEDIA_TYPE_IMAGE"),
        )
        val raw = core.message("m-1")!!
        assertArrayEquals(byteArrayOf(0x43, 0x54, 0x4D, 0x31, 0x03), raw.body.copyOfRange(0, 5))
        assertEquals("me", raw.fromUserId)
        assertEquals("peer", raw.toUserId)
        assertEquals(2.toShort(), raw.deliveryStatus)
        assertEquals("q", raw.replyToMessageId)
        assertEquals("image", JSONObject(raw.replyToContent!!).getString("kind"))
        assertEquals(com.construct.messenger.util.ServerMessageOrder.local(5, "m-1"), raw.orderKey)
    }
}
