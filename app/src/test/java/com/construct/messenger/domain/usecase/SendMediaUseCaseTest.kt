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
        override suspend fun stage(localId: String, blob: ByteArray) { staged[localId] = blob }
        override suspend fun upload(localId: String, sha256: ByteArray): MediaService.Uploaded {
            if (fail) throw io.grpc.StatusException(io.grpc.Status.UNAVAILABLE)
            assertTrue(sha256.contentEquals(MediaCrypto.sha256(staged.getValue(localId))))
            return MediaService.Uploaded("store-${staged.keys.indexOf(localId)}", "grpc://media/MediaService/DownloadMedia")
        }
    }

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

        SendMediaUseCase(images, media, send).photos("peer", listOf(mock<Uri>(), mock<Uri>()), " look ", null)

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

    /** Nothing the recipient could not open is sent: a failed upload fails the message. */
    @Test
    fun `a failed upload fails the message and sends nothing`() = runTest {
        val send: SendMessageUseCase = mock()
        val outcome = SendMediaUseCase(images, FakeMedia(fail = true), send).photos("peer", listOf(mock<Uri>()), "", null)

        assertTrue(outcome is SendOutcome.Failed)
        verify(send).markFailed(any())
        verify(send, never()).deliverPrepared(any(), any(), any(), any())
    }
}
