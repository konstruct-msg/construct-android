package com.construct.messenger.util

import com.construct.messenger.ui.components.VideoNoteLayout
import com.google.protobuf.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import shared.proto.messaging.v1.Content.MediaAlbumMessage
import shared.proto.messaging.v1.Content.MediaMessage
import shared.proto.messaging.v1.Content.MediaPresentation
import shared.proto.messaging.v1.Content.MessageContent

/** `MediaMessage.presentation` (construct-protos `b622de1`); construct-docs TODO 111. **Canon:** iOS `VideoNotePresentationTests`. */
class VideoNoteWireTest {

    private fun item(mime: String, presentation: Int? = null, id: String = "m1") = MediaMessage.newBuilder()
        .setMediaId(id)
        .setEncryptionKey(ByteString.copyFrom(ByteArray(32)))
        .setMimeType(mime)
        .setMediaType(MediaWire.mediaTypeOf(mime))
        .apply { presentation?.let { setPresentationValue(it) } }
        .build()

    private fun album(vararg items: MediaMessage) =
        MediaWire.stored(MessageContent.newBuilder().setMediaAlbum(MediaAlbumMessage.newBuilder().addAllItems(items.toList())).build())!!
            .let { MediaWire.decode(it.kind, it.bytes) as com.construct.messenger.data.model.MessageMedia.Album }

    @Test
    fun `a marked single video is a note, through the stored row`() {
        val a = album(item("video/mp4", MediaPresentation.MEDIA_PRESENTATION_VIDEO_NOTE_VALUE))
        assertNotNull(a.videoNote)
    }

    /** A value this build does not know is the ordinary bubble — what the field promises. */
    @Test
    fun `an unknown presentation is an ordinary video`() {
        assertNull(album(item("video/mp4", 7)).videoNote)
        assertNull(album(item("video/mp4")).videoNote)
    }

    /** Mutation: drop the video check — a marked photo would play as a note. */
    @Test
    fun `the mark on anything but one video is ignored`() {
        assertNull(album(item("image/jpeg", MediaPresentation.MEDIA_PRESENTATION_VIDEO_NOTE_VALUE)).videoNote)
        val v = MediaPresentation.MEDIA_PRESENTATION_VIDEO_NOTE_VALUE
        assertNull(album(item("video/mp4", v, "a"), item("video/mp4", v, "b")).videoNote)
    }

    @Test
    fun `a note keeps its own shape, 3 by 4 when it has none`() {
        assertEquals(0.75f, VideoNoteLayout.aspect(null, null))
        assertEquals(0.75f, VideoNoteLayout.aspect(720, 960))
        assertEquals(16f / 9f, VideoNoteLayout.aspect(1920, 1080))
        assertEquals(0.75f, VideoNoteLayout.aspect(0, 960))
    }
}
