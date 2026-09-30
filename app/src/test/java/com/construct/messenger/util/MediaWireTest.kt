package com.construct.messenger.util

import com.construct.messenger.data.model.MessageMedia
import com.google.protobuf.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import shared.proto.messaging.v1.Content.MediaAlbumMessage
import shared.proto.messaging.v1.Content.MediaDimensions
import shared.proto.messaging.v1.Content.MediaMessage
import shared.proto.messaging.v1.Content.MediaType
import shared.proto.messaging.v1.Content.MessageContent
import shared.proto.messaging.v1.Content.VoiceMessage

class MediaWireTest {

    private val key = ByteString.copyFrom(ByteArray(32) { 7 })

    private fun photo(id: String) = MediaMessage.newBuilder()
        .setMediaType(MediaType.MEDIA_TYPE_IMAGE)
        .setFileUrl("grpc://media/MediaService/DownloadMedia")
        .setEncryptionKey(key)
        .setFileSize(1234)
        .setMimeType("image/jpeg")
        .setDimensions(MediaDimensions.newBuilder().setWidth(1920).setHeight(1080))
        .setBlurhash("LEHV6nWB2yk8pyo0adR*.7kCMdnj")
        .setMediaId(id)
        .build()

    @Test
    fun `an album keeps its items and its caption`() {
        val content = MessageContent.newBuilder().setMediaAlbum(
            MediaAlbumMessage.newBuilder().addItems(photo("a")).addItems(photo("b")).setCaption("two"),
        ).build()
        val stored = MediaWire.stored(content)!!
        assertEquals("two", stored.caption)
        val album = MediaWire.decode(stored.kind, stored.bytes) as MessageMedia.Album
        assertEquals(listOf("a", "b"), album.items.map { it.mediaId })
        assertEquals(1920, album.items[0].width)
        assertEquals("LEHV6nWB2yk8pyo0adR*.7kCMdnj", album.items[0].blurhash)
        assertTrue(!album.isFiles)
    }

    /** iOS reads a lone `media` as a one-item album (`ChunkedMessageReassembler.extract`). */
    @Test
    fun `a single media is an album of one`() {
        val stored = MediaWire.stored(MessageContent.newBuilder().setMedia(photo("x").toBuilder().setCaption("c")).build())!!
        assertEquals("c", stored.caption)
        assertEquals(1, (MediaWire.decode(stored.kind, stored.bytes) as MessageMedia.Album).items.size)
    }

    /**
     * iOS has no media id field for voice and carries it in `codec` as `mime|mediaId|size`.
     * Mutation: read the id from `file_url` — this reddens.
     */
    @Test
    fun `a voice note's id comes out of its codec`() {
        val voice = VoiceMessage.newBuilder()
            .setFileUrl("grpc://media/MediaService/DownloadMedia")
            .setEncryptionKey(key)
            .setDurationMs(4200)
            .addAllWaveform(listOf(0, 128, 300))
            .setCodec("audio/m4a|3f2a0000-0000-4000-8000-000000000001|48213")
            .build()
        val stored = MediaWire.stored(MessageContent.newBuilder().setVoice(voice).build())!!
        val decoded = MediaWire.decode(stored.kind, stored.bytes) as MessageMedia.Voice
        assertEquals("3f2a0000-0000-4000-8000-000000000001", decoded.audio.mediaId)
        assertEquals("audio/m4a", decoded.audio.mimeType)
        assertEquals(48213L, decoded.audio.sizeBytes)
        assertEquals(4200L, decoded.audio.durationMs)
        assertEquals(listOf(0, 128, 255), decoded.waveform)
    }

    @Test
    fun `an item without a usable key is not an item`() {
        val bad = photo("y").toBuilder().setEncryptionKey(ByteString.copyFrom(ByteArray(16))).build()
        val stored = MediaWire.stored(MessageContent.newBuilder().setMediaAlbum(MediaAlbumMessage.newBuilder().addItems(bad)).build())!!
        assertNull(MediaWire.decode(stored.kind, stored.bytes))
    }

    @Test
    fun `an album of documents is a file message`() {
        val doc = photo("d").toBuilder().setMimeType("application/pdf").setFilename("a.pdf").build()
        val stored = MediaWire.stored(MessageContent.newBuilder().setMediaAlbum(MediaAlbumMessage.newBuilder().addItems(doc)).build())!!
        val album = MediaWire.decode(stored.kind, stored.bytes) as MessageMedia.Album
        assertTrue(album.isFiles)
        assertEquals("a.pdf", album.items[0].filename)
    }
}
