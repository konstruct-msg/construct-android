package com.construct.messenger.data.model

import com.construct.messenger.stickers.StickerReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What a forward carries: the content, never the author, the quote or the time (TODO 143). */
class ForwardItemTest {
    private fun item(id: String) = MediaItem(id, ByteArray(32), ByteArray(32), 1, "image/jpeg")

    /** A reply forwards as its text alone. Mutation: carry the quote — the type has no room for it. */
    @Test
    fun aReplyIsItsTextAlone() {
        val reply = Message(id = "m", chatId = "c", body = "  yes  ", isOutgoing = false, replyToId = "o", replyPreview = "q")
        assertEquals(ForwardItem.Text("yes"), ForwardItem.of(reply))
    }

    @Test
    fun anEmptyMessageHasNothingToForward() {
        assertNull(ForwardItem.of(Message(id = "m", chatId = "c", body = " ", isOutgoing = true)))
    }

    @Test
    fun anAlbumKeepsItsCaption() {
        val photos = Message(id = "m", chatId = "c", body = "look", isOutgoing = true, media = MessageMedia.Album(listOf(item("a"), item("b"))))
        assertEquals(ForwardItem.Album(listOf(item("a"), item("b")), "look"), ForwardItem.of(photos))
    }

    @Test
    fun aVoiceNoteAndAStickerGoAsThemselves() {
        val voice = MessageMedia.Voice(item("v"), listOf(1, 2))
        assertEquals(ForwardItem.Voice(item("v"), listOf(1, 2)), ForwardItem.of(Message(id = "m", chatId = "c", body = "", isOutgoing = true, media = voice)))
        val ref = StickerReference.of(ByteArray(32), 1, "😀")!!
        assertEquals(ForwardItem.Sticker(ref), ForwardItem.of(Message(id = "m", chatId = "c", body = "", isOutgoing = true, media = MessageMedia.Sticker(ref))))
    }

    @Test
    fun severalKeepTheirOrderAndDropWhatIsEmpty() {
        val msgs = listOf(
            Message(id = "1", chatId = "c", body = "first", isOutgoing = true),
            Message(id = "2", chatId = "c", body = "", isOutgoing = true),
            Message(id = "3", chatId = "c", body = "third", isOutgoing = false),
        )
        assertEquals(listOf(ForwardItem.Text("first"), ForwardItem.Text("third")), ForwardItem.of(msgs))
    }
}
