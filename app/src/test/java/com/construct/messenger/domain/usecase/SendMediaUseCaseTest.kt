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

private fun sha256(bytes: ByteArray): ByteArray = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)

class SendMediaUseCaseTest {

    private class FakeMedia(val fail: Boolean = false) : MediaRepository {
        val staged = linkedMapOf<String, ByteArray>()
        override suspend fun bytes(item: MediaItem) = error("not read here")
        override suspend fun openable(item: MediaItem, name: String): Uri = error("not opened here")
        override suspend fun saveToGallery(item: MediaItem) = error("not here")
        override suspend fun stage(localId: String, blob: ByteArray) { staged[localId] = blob }
        val dropped = mutableListOf<String>()
        override suspend fun dropCopy(mediaId: String) { dropped += mediaId }
        override suspend fun upload(localId: String, sha256: ByteArray): MediaService.Uploaded {
            if (fail) throw io.grpc.StatusException(io.grpc.Status.UNAVAILABLE)
            assertTrue(sha256.contentEquals(sha256(staged.getValue(localId))))
            return MediaService.Uploaded("store-${staged.keys.indexOf(localId)}", "grpc://media/MediaService/DownloadMedia")
        }
    }

    private val pickedFiles: com.construct.messenger.media.PickedFiles = mock<com.construct.messenger.media.PickedFiles>().also {
        whenever(it.read(any())).thenReturn(com.construct.messenger.media.PickedFiles.Picked("report.pdf", "application/pdf", ByteArray(700) { 5 }))
    }

    private val videos: com.construct.messenger.media.VideoPreparer = mock()

    /** No lasting grant unless a test gives one: the app's copy stays. */
    private val originals: com.construct.messenger.media.OwnOriginals = mock()

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

        SendMediaUseCase(images, pickedFiles, videos, media, send, originals).photos("peer", listOf(mock<Uri>(), mock<Uri>()), " look ", null)

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
        assertTrue(first.fileHash.toByteArray().contentEquals(sha256(blob)))
        assertEquals(100, MediaCrypto.open(blob, first.encryptionKey.toByteArray()).size)
        assertEquals(1920, first.dimensions.width)
        assertEquals("LEHV6nWB2yk8pyo0adR*.7kCMdnj", first.blurhash)
        assertFalse(first.hasThumbnail())
    }

    /**
     * A video note goes the video path with the mark, through the note's own preparation (the
     * centre 3:4 at 720×960), and the recording is deleted. The placeholder carries the mark too.
     * Mutation: prepare it as an ordinary video, or drop the mark — reddens.
     */
    @Test
    fun `a video note is a marked video, prepared as a note`() = runTest {
        val media = FakeMedia()
        val send: SendMessageUseCase = mock()
        whenever(send.deliverPrepared(any(), any(), any(), any())).thenReturn(SendOutcome.Sent("m"))
        val notes: com.construct.messenger.media.VideoPreparer = mock()
        whenever(notes.prepareNote(any())).thenReturn(
            com.construct.messenger.media.VideoPreparer.Prepared(ByteArray(300) { 2 }, 720, 960, 4_000, ByteArray(10), "LEHV6nWB2yk8pyo0adR*.7kCMdnj"),
        )
        val file = java.io.File.createTempFile("note", ".mp4").apply { writeBytes(ByteArray(10)) }
        val take = com.construct.messenger.media.VideoNoteTake(listOf(com.construct.messenger.media.VideoNoteTake.Segment(file, 4_000)), 500L..3_500L)

        val reply = com.construct.messenger.data.model.ReplyRef.of("abc", "original")
        SendMediaUseCase(images, pickedFiles, notes, media, send, originals).videoNote("peer", take, reply)

        verify(notes, never()).prepare(org.mockito.kotlin.anyOrNull(), org.mockito.kotlin.anyOrNull())
        verify(notes).prepareNote(take)
        val shown = argumentCaptor<MediaWire.Stored>()
        verify(send).persistMedia(eq("peer"), any(), any(), shown.capture(), eq(reply))
        val placeholder = MediaWire.decode(shown.firstValue.kind, shown.firstValue.bytes) as com.construct.messenger.data.model.MessageMedia.Album
        assertTrue(placeholder.videoNote != null)
        val content = argumentCaptor<ByteArray>()
        verify(send).deliverPrepared(eq("peer"), any(), any(), content.capture())
        // The answer travels with the note, as with a photo. Mutation: pass null on — reddens.
        assertEquals("abc", MessageContent.parseFrom(content.firstValue).mediaAlbum.quoted.messageId)
        val item = MessageContent.parseFrom(content.firstValue).mediaAlbum.getItems(0)
        assertEquals(shared.proto.messaging.v1.Content.MediaPresentation.MEDIA_PRESENTATION_VIDEO_NOTE, item.presentation)
        assertEquals(MediaType.MEDIA_TYPE_VIDEO, item.mediaType)
        assertEquals(720, item.dimensions.width)
        assertEquals(960, item.dimensions.height)
        assertFalse(file.exists())
    }

    /**
     * A voice note recorded while quoting answers that message: the quote rides inside the voice
     * on the wire and on the row. Mutation: leave `setQuoted` out, or persist with `null` — reddens.
     */
    @Test
    fun `a voice note carries the quote it answers`() = runTest {
        val media = FakeMedia()
        val send: SendMessageUseCase = mock()
        whenever(send.deliverPrepared(any(), any(), any(), any())).thenReturn(SendOutcome.Sent("m"))
        val reply = com.construct.messenger.data.model.ReplyRef.of("ABC", "", "MEDIA_TYPE_VIDEO", videoNote = true)
        val file = java.io.File.createTempFile("voice", ".m4a").apply { writeBytes(ByteArray(10)) }

        SendMediaUseCase(images, pickedFiles, videos, media, send, originals).voice("peer", file, 900, emptyList(), reply)

        val content = argumentCaptor<ByteArray>()
        verify(send).deliverPrepared(eq("peer"), any(), any(), content.capture())
        val quoted = MessageContent.parseFrom(content.firstValue).voice.quoted
        assertEquals("abc", quoted.messageId)
        assertEquals(MediaType.MEDIA_TYPE_VIDEO, quoted.mediaType)
        verify(send).persistMedia(eq("peer"), any(), any(), any(), eq(reply))
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

        SendMediaUseCase(images, pickedFiles, videos, media, send, originals).voice("peer", file, 4200, listOf(0f, 0.5f, 1f))

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

        SendMediaUseCase(images, pickedFiles, videos, media, send, originals).files("peer", listOf(mock<Uri>()), "q3", null)

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
     * A file the picker granted for good is kept as a link, with its plaintext's size and hash, and
     * the app's copy goes; without a grant the copy stays (TODO 154). Mutation: drop the copy
     * whatever `remember` answers — reddens.
     */
    @Test
    fun `a sent file is linked where it is, and the copy kept only without a grant`() = runTest {
        val send: SendMessageUseCase = mock()
        whenever(send.deliverPrepared(any(), any(), any(), any())).thenReturn(SendOutcome.Sent("m"))
        val picked: Uri = mock()

        val kept = FakeMedia()
        SendMediaUseCase(images, pickedFiles, videos, kept, send, originals).files("peer", listOf(picked), "", null)
        assertTrue("no grant: the copy stays", kept.dropped.isEmpty())

        val linked = FakeMedia()
        whenever(originals.remember(any(), any(), any(), any())).thenReturn(true)
        SendMediaUseCase(images, pickedFiles, videos, linked, send, originals).files("peer", listOf(picked), "", null)
        assertEquals(listOf("store-0"), linked.dropped)
        verify(originals, org.mockito.kotlin.times(2)).remember(eq("store-0"), eq(picked), eq(700L), org.mockito.kotlin.argThat { contentEquals(sha256(ByteArray(700) { 5 })) })
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

        SendMediaUseCase(images, pickedFiles, videos, media, send, originals).photos("peer", listOf(clip), "", null)

        val content = argumentCaptor<ByteArray>()
        verify(send).deliverPrepared(eq("peer"), any(), any(), content.capture())
        val item = MessageContent.parseFrom(content.firstValue).mediaAlbum.getItems(0)
        assertEquals(MediaType.MEDIA_TYPE_VIDEO, item.mediaType)
        assertEquals("video/mp4", item.mimeType)
        assertEquals(12_345, item.durationMs)
        assertEquals(listOf<Byte>(1, 2, 3), item.thumbnail.toByteArray().toList())
        assertEquals(1920, item.dimensions.height)
    }

    /** The quality chosen in the composer is the one the video is prepared at (TODO 148). */
    @Test
    fun `a video is prepared at the chosen quality`() = runTest {
        val send: SendMessageUseCase = mock()
        whenever(send.deliverPrepared(any(), any(), any(), any())).thenReturn(SendOutcome.Sent("m"))
        val clip: Uri = mock()
        whenever(videos.isVideo(clip)).thenReturn(true)
        whenever(videos.prepare(any(), any())).thenReturn(
            com.construct.messenger.media.VideoPreparer.Prepared(ByteArray(900) { 9 }, 720, 1280, 1_000, null, null),
        )

        SendMediaUseCase(images, pickedFiles, videos, FakeMedia(), send, originals)
            .photos("peer", listOf(clip), "", null, com.construct.messenger.media.VideoQuality.ORIGINAL)

        verify(videos).prepare(clip, com.construct.messenger.media.VideoQuality.ORIGINAL)
    }

    /** Nothing the recipient could not open is sent: a failed upload fails the message. */
    @Test
    fun `a failed upload fails the message and sends nothing`() = runTest {
        val send: SendMessageUseCase = mock()
        val outcome = SendMediaUseCase(images, pickedFiles, videos, FakeMedia(fail = true), send, originals).photos("peer", listOf(mock<Uri>()), "", null)

        assertTrue(outcome is SendOutcome.Failed)
        verify(send).markFailed(any())
        verify(send, never()).deliverPrepared(any(), any(), any(), any())
    }

    /**
     * A forward (TODO 143) goes as new content: each item sealed again under a fresh key and
     * uploaded as a new object, described as the original was, with no quote. Nothing of the
     * original's object — id, key, digest — is on the wire. Mutation: reuse the original's key or
     * id — reddens.
     */
    @Test
    fun `a forward is a new object under a new key with the original's description`() = runTest {
        val media = FakeMedia()
        val send: SendMessageUseCase = mock()
        whenever(send.deliverPrepared(any(), any(), any(), any())).thenReturn(SendOutcome.Sent("m"))
        val originalKey = ByteArray(32) { 9 }
        val original = MediaItem(
            mediaId = "original-object", key = originalKey, hash = ByteArray(32) { 8 }, sizeBytes = 1234,
            mimeType = "video/mp4", width = 720, height = 960, durationMs = 4_000, blurhash = "LEHV6nWB2yk8pyo0adR*.7kCMdnj",
            thumbnail = ByteArray(10) { 3 }, isVideoNote = true,
        )
        val plain = ByteArray(300) { 2 }

        SendMediaUseCase(images, pickedFiles, videos, media, send, originals).forward("peer", listOf(original), listOf(plain), "")

        verify(send).persistMedia(eq("peer"), any(), any(), any(), eq(null))
        val content = argumentCaptor<ByteArray>()
        verify(send).deliverPrepared(eq("peer"), any(), any(), content.capture())
        val album = MessageContent.parseFrom(content.firstValue).mediaAlbum
        assertFalse("no quote", album.hasQuoted())
        val item = album.getItems(0)
        assertEquals("store-0", item.mediaId)
        assertFalse("a new key", item.encryptionKey.toByteArray().contentEquals(originalKey))
        assertFalse(item.fileHash.toByteArray().contentEquals(original.hash))
        assertTrue(plain.contentEquals(MediaCrypto.open(media.staged.values.first(), item.encryptionKey.toByteArray())))
        assertEquals(shared.proto.messaging.v1.Content.MediaPresentation.MEDIA_PRESENTATION_VIDEO_NOTE, item.presentation)
        assertEquals(MediaType.MEDIA_TYPE_VIDEO, item.mediaType)
        assertEquals(720, item.dimensions.width)
        assertEquals(4_000, item.durationMs)
        assertEquals(10, item.thumbnail.size())
    }

    /** A file keeps its name and is counted by its own size, as on its first send. */
    @Test
    fun `a forwarded file keeps its name`() = runTest {
        val media = FakeMedia()
        val send: SendMessageUseCase = mock()
        whenever(send.deliverPrepared(any(), any(), any(), any())).thenReturn(SendOutcome.Sent("m"))
        val doc = MediaItem("orig", ByteArray(32) { 1 }, ByteArray(32), 700, "application/pdf", filename = "report.pdf")

        SendMediaUseCase(images, pickedFiles, videos, media, send, originals).forward("peer", listOf(doc), listOf(ByteArray(700) { 5 }), "q")

        val content = argumentCaptor<ByteArray>()
        verify(send).deliverPrepared(eq("peer"), any(), any(), content.capture())
        val album = MessageContent.parseFrom(content.firstValue).mediaAlbum
        assertEquals("q", album.caption)
        assertEquals("report.pdf", album.getItems(0).filename)
        assertEquals(MediaType.MEDIA_TYPE_FILE, album.getItems(0).mediaType)
        assertEquals(700L, album.getItems(0).fileSize)
    }
}
