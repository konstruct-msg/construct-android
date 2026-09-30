package com.construct.messenger.domain.usecase

import android.net.Uri
import com.construct.messenger.data.api.MediaService
import com.construct.messenger.data.model.MediaItem
import com.construct.messenger.data.repository.MediaRepository
import com.construct.messenger.media.ImagePreparer
import com.construct.messenger.media.MediaCrypto
import com.construct.messenger.util.MediaWire
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import shared.proto.messaging.v1.Content.MediaType
import shared.proto.messaging.v1.Content.MessageContent

class SendMediaUseCaseTest {

    private class FakeMedia(val fail: Boolean = false) : MediaRepository {
        val staged = linkedMapOf<String, ByteArray>()
        override suspend fun bytes(item: MediaItem) = error("not read here")
        override suspend fun openable(item: MediaItem, name: String): Uri = error("not opened here")
        override suspend fun stage(localId: String, blob: ByteArray) { staged[localId] = blob }
        override suspend fun upload(localId: String, sha256: ByteArray): MediaService.Uploaded {
            if (fail) throw io.grpc.StatusException(io.grpc.Status.UNAVAILABLE)
            assertTrue(sha256.contentEquals(MediaCrypto.sha256(staged.getValue(localId))))
            return MediaService.Uploaded("store-${staged.keys.indexOf(localId)}", "grpc://media/MediaService/DownloadMedia")
        }
    }

    private val pickedFiles: com.construct.messenger.media.PickedFiles = mock<com.construct.messenger.media.PickedFiles>().also {
        whenever(it.read(any())).thenReturn(com.construct.messenger.media.PickedFiles.Picked("report.pdf", "application/pdf", ByteArray(700) { 5 }))
    }

    private val videos: com.construct.messenger.media.VideoPreparer = mock()

    private val images: ImagePreparer = mock<ImagePreparer>().also {
        whenever(it.prepare(any())).thenReturn(ImagePreparer.Prepared(ByteArray(100) { 1 }, 1920, 1080, "LEHV6nWB2yk8pyo0adR*.7kCMdnj"))
    }

    /**
     * What goes on the wire is iOS's album: one item per photo, in order, the store's ids, the
     * key that opens each blob, its digest and size, pixel size and BlurHash, the caption on the
     * album. The row shows first, under local ids. Mutation: send before the uploads — reddens.
     */
    @Test
    fun `photos go as one album with the store's ids`() = runTest {
        val media = FakeMedia()
        val send: SendMessageUseCase = mock()
        whenever(send.deliverPrepared(any(), any(), any(), any())).thenReturn(SendOutcome.Sent("m"))

        SendMediaUseCase(images, pickedFiles, videos, media, send).photos("peer", listOf(mock<Uri>(), mock<Uri>()), " look ", null)

        val shown = argumentCaptor<MediaWire.Stored>()
        verify(send).persistMedia(eq("peer"), any(), any(), shown.capture(), eq(null))
        assertTrue(MediaWire.isStaged(MediaWire.decode(shown.firstValue.kind, shown.firstValue.bytes)!!))

        val content = argumentCaptor<ByteArray>()
        verify(send).deliverPrepared(eq("peer"), any(), any(), content.capture())
        val album = MessageContent.parseFrom(content.firstValue).mediaAlbum
        assertEquals("look", album.caption)
        assertEquals(listOf("store-0", "store-1"), album.itemsList.map { it.mediaId })
        val first = album.getItems(0)
        val blob = media.staged.values.first()
        assertEquals(MediaType.MEDIA_TYPE_IMAGE, first.mediaType)
        assertEquals("image/jpeg", first.mimeType)
        assertEquals(blob.size.toLong(), first.fileSize)
        assertTrue(first.fileHash.toByteArray().contentEquals(MediaCrypto.sha256(blob)))
        assertEquals(100, MediaCrypto.open(blob, first.encryptionKey.toByteArray()).size)
        assertEquals(1920, first.dimensions.width)
        assertEquals("LEHV6nWB2yk8pyo0adR*.7kCMdnj", first.blurhash)
        assertFalse(first.hasThumbnail())
    }

    /**
     * iOS has no media id field for voice and reads it out of `codec` as `mime|id|size`; the
     * waveform goes as 0–255. Mutation: put the blob's size in the codec — reddens.
     */
    @Test
    fun `a voice note carries its id in the codec, as iOS reads it`() = runTest {
        val media = FakeMedia()
        val send: SendMessageUseCase = mock()
        whenever(send.deliverPrepared(any(), any(), any(), any())).thenReturn(SendOutcome.Sent("m"))
        val file = java.io.File.createTempFile("voice", ".m4a").apply { writeBytes(ByteArray(500) { 3 }) }

        SendMediaUseCase(images, pickedFiles, videos, media, send).voice("peer", file, 4200, listOf(0f, 0.5f, 1f))

        assertFalse(file.exists())
        val content = argumentCaptor<ByteArray>()
        verify(send).deliverPrepared(eq("peer"), any(), any(), content.capture())
        val voice = MessageContent.parseFrom(content.firstValue).voice
        assertEquals("audio/m4a|store-0|500", voice.codec)
        assertEquals(4200, voice.durationMs)
        assertEquals(listOf(0, 128, 255), voice.waveformList)
        assertEquals(500, MediaCrypto.open(media.staged.values.first(), voice.encryptionKey.toByteArray()).size)
    }

    /**
     * A document goes as iOS sends one: an album item of type FILE with its name, its own type
     * and its own size, uncompressed. Mutation: send the blob's size — reddens.
     */
    @Test
    fun `a file goes with its name, type and own size`() = runTest {
        val media = FakeMedia()
        val send: SendMessageUseCase = mock()
        whenever(send.deliverPrepared(any(), any(), any(), any())).thenReturn(SendOutcome.Sent("m"))

        SendMediaUseCase(images, pickedFiles, videos, media, send).files("peer", listOf(mock<Uri>()), "q3")

        val content = argumentCaptor<ByteArray>()
        verify(send).deliverPrepared(eq("peer"), any(), any(), content.capture())
        val album = MessageContent.parseFrom(content.firstValue).mediaAlbum
        val item = album.getItems(0)
        assertEquals("q3", album.caption)
        assertEquals(MediaType.MEDIA_TYPE_FILE, item.mediaType)
        assertEquals("report.pdf", item.filename)
        assertEquals("application/pdf", item.mimeType)
        assertEquals(700L, item.fileSize)
        assertEquals(700, MediaCrypto.open(media.staged.values.first(), item.encryptionKey.toByteArray()).size)
    }

    /**
     * A video goes as iOS sends one: type VIDEO, `video/mp4`, its length, and always its poster
     * as the thumbnail (iOS `InlinePreviewPolicy`). Mutation: drop the thumbnail — reddens.
     */
    @Test
    fun `a video in the album carries its poster and length`() = runTest {
        val media = FakeMedia()
        val send: SendMessageUseCase = mock()
        whenever(send.deliverPrepared(any(), any(), any(), any())).thenReturn(SendOutcome.Sent("m"))
        val clip: Uri = mock()
        whenever(videos.isVideo(clip)).thenReturn(true)
        whenever(videos.prepare(clip)).thenReturn(
            com.construct.messenger.media.VideoPreparer.Prepared(ByteArray(900) { 9 }, 1080, 1920, 12_345, byteArrayOf(1, 2, 3), "LEHV6nWB2yk8pyo0adR*.7kCMdnj"),
        )

        SendMediaUseCase(images, pickedFiles, videos, media, send).photos("peer", listOf(clip), "", null)

        val content = argumentCaptor<ByteArray>()
        verify(send).deliverPrepared(eq("peer"), any(), any(), content.capture())
        val item = MessageContent.parseFrom(content.firstValue).mediaAlbum.getItems(0)
        assertEquals(MediaType.MEDIA_TYPE_VIDEO, item.mediaType)
        assertEquals("video/mp4", item.mimeType)
        assertEquals(12_345, item.durationMs)
        assertEquals(listOf<Byte>(1, 2, 3), item.thumbnail.toByteArray().toList())
        assertEquals(1920, item.dimensions.height)
    }

    /** Nothing the recipient could not open is sent: a failed upload fails the message. */
    @Test
    fun `a failed upload fails the message and sends nothing`() = runTest {
        val send: SendMessageUseCase = mock()
        val outcome = SendMediaUseCase(images, pickedFiles, videos, FakeMedia(fail = true), send).photos("peer", listOf(mock<Uri>()), "", null)

        assertTrue(outcome is SendOutcome.Failed)
        verify(send).markFailed(any())
        verify(send, never()).deliverPrepared(any(), any(), any(), any())
    }
}
