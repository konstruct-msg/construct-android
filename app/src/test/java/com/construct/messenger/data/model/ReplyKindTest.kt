package com.construct.messenger.data.model

import com.construct.messenger.util.TextWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import shared.proto.messaging.v1.Content.MediaType

/** **Canon:** iOS `ReplyPreviewPayload` — a quote names its media by kind; a note is local only. */
class ReplyKindTest {
    private fun item(mime: String, note: Boolean = false) =
        MediaItem(mediaId = "m", key = ByteArray(32), hash = ByteArray(0), sizeBytes = 1, mimeType = mime, isVideoNote = note)

    /** Mutation: quote an album with no type — the strip says "message unavailable" for a photo. */
    @Test
    fun `an album is quoted by its first item`() {
        assertEquals("MEDIA_TYPE_IMAGE", ReplyRef.mediaTypeOf(MessageMedia.Album(listOf(item("image/jpeg")))))
        assertEquals("MEDIA_TYPE_VIDEO", ReplyRef.mediaTypeOf(MessageMedia.Album(listOf(item("video/mp4"), item("image/jpeg")))))
        assertEquals("MEDIA_TYPE_FILE", ReplyRef.mediaTypeOf(MessageMedia.Album(listOf(item("application/pdf")))))
        assertNull(ReplyRef.mediaTypeOf(null))
    }

    @Test
    fun `a video note goes on the wire as a video`() {
        val ref = ReplyRef.of("ID", "", ReplyRef.mediaTypeOf(MessageMedia.Album(listOf(item("video/mp4", note = true)))), videoNote = true)!!
        assertEquals(MediaType.MEDIA_TYPE_VIDEO, TextWire.quoted(ref)!!.mediaType)
    }
}
