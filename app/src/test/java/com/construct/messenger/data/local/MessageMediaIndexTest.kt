package com.construct.messenger.data.local

import com.construct.messenger.data.model.DeliveryStatus
import com.construct.messenger.util.MediaWire
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import shared.proto.messaging.v1.Content.MediaAlbumMessage
import shared.proto.messaging.v1.Content.MediaMessage
import shared.proto.messaging.v1.Content.MediaPresentation
import shared.proto.messaging.v1.Content.MessageContent
import shared.proto.messaging.v1.Content.StickerRef
import shared.proto.messaging.v1.Content.TextMessage
import shared.proto.messaging.v1.Content.VoiceMessage
import uniffi.construct_core.LocalMediaEntry
import uniffi.construct_core.LocalMessage
import uniffi.construct_core.LocalStore

/**
 * The store's media index, as this app fills it (vault todo 327) — the same claims as iOS
 * `MessageMediaIndexTests`. Each test names the mutation that must redden it.
 */
class MessageMediaIndexTest {
    private fun item(id: String, mime: String, size: Long = 10, videoNote: Boolean = false): MediaMessage =
        MediaMessage.newBuilder().setMediaId(id).setMimeType(mime).setFileSize(size).apply {
            if (videoNote) presentation = MediaPresentation.MEDIA_PRESENTATION_VIDEO_NOTE
        }.build()

    private fun album(vararg items: MediaMessage): MediaAlbumMessage =
        MediaAlbumMessage.newBuilder().addAllItems(items.toList()).build()

    private fun content(c: MessageContent): ByteArray =
        LocalMessagePayload.envelope(LocalMessagePayload.KIND_MESSAGE_CONTENT, c.toByteArray())

    private fun voiceBody(mediaId: String, size: Long): ByteArray =
        content(MessageContent.newBuilder().setVoice(VoiceMessage.newBuilder().setCodec("audio/m4a|$mediaId|$size")).build())

    private fun summary(entries: List<LocalMediaEntry>?): List<String>? =
        entries?.map { "${it.item}:${it.mediaId}:${it.kind}:${it.byteSize}" }

    // What a body holds

    /**
     * Each item by its own kind, at its own position; one with no id is left out and the rest keep
     * their positions. Mutations: a round video read as a video, a GIF as a photo, a PDF as a
     * photo, or positions counted after the gap — each reddens.
     */
    @Test
    fun anAlbumIsIndexedItemByItemInItsOwnPositions() {
        val body = LocalMessagePayload.envelope(
            LocalMessagePayload.KIND_MEDIA_ALBUM,
            album(
                item("p", "image/jpeg", 1), item("", "image/jpeg"), item("v", "video/mp4", 3),
                item("n", "video/mp4", 4, videoNote = true), item("g", "image/gif", 5), item("d", "application/pdf", 6),
            ).toByteArray(),
        )
        assertEquals(
            listOf("0:p:PHOTO:1", "2:v:VIDEO:3", "3:n:VIDEO_NOTE:4", "4:g:ANIMATION:5", "5:d:FILE:6"),
            summary(MessageMediaIndex.entries(body)),
        )
    }

    /**
     * Everything Android writes is a message content: an album, one media, or a voice note whose id
     * rides in `codec`. A text or a sticker is indexed empty, so the backfill stops listing it.
     * Mutations: drop the content album, the single media, or the voice case; return null for a
     * content text — each reddens.
     */
    @Test
    fun aMessageContentIsIndexedByWhatItHolds() {
        val albumBody = content(MessageContent.newBuilder().setMediaAlbum(album(item("a", "image/png", 7))).build())
        assertEquals(listOf("0:a:PHOTO:7"), summary(MessageMediaIndex.entries(albumBody)))
        val single = content(MessageContent.newBuilder().setMedia(item("s", "video/quicktime", 8)).build())
        assertEquals(listOf("0:s:VIDEO:8"), summary(MessageMediaIndex.entries(single)))
        assertEquals(listOf("0:vn:VOICE:42"), summary(MessageMediaIndex.entries(voiceBody("vn", 42))))

        val text = content(MessageContent.newBuilder().setText(TextMessage.newBuilder().setText("hi")).build())
        assertEquals(emptyList<String>(), summary(MessageMediaIndex.entries(text)))
        val sticker = content(MessageContent.newBuilder().setSticker(StickerRef.getDefaultInstance()).build())
        assertEquals(emptyList<String>(), summary(MessageMediaIndex.entries(sticker)))
    }

    /** A plain text, a profile or a body from before the envelope is not asked about. Mutation: `[]` for them. */
    @Test
    fun aTextOrAnOlderBodyIsNotAskedAbout() {
        assertNull(MessageMediaIndex.entries(LocalMessagePayload.envelope(LocalMessagePayload.KIND_TEXT, "hello".toByteArray())))
        assertNull(MessageMediaIndex.entries(LocalMessagePayload.envelope(LocalMessagePayload.KIND_PROFILE, byteArrayOf(1))))
        assertNull(MessageMediaIndex.entries("{\"type\":\"media\"}".toByteArray()))
    }

    // Written with the message

    private val core = LocalStore.inMemory(ByteArray(32) { 1 })
    private val feed = LocalStoreFeed(core)
    private val messages = CoreMessageStore(feed, myUserId = { "me" })
    private val peer = "0b6e9f6a-3c1d-4f7e-9a2b-5d8c7e6f1a2b"
    private val chat = "chat-0b6e9f6a"

    @Before
    fun openChat() = runTest {
        CoreContactStore(feed, { null }).ensure(peer)
        CoreChatStore(feed, { null }).ensure(chat, peer)
    }

    @After
    fun close() = core.close()

    private fun gallery(): List<String> = core.chatMedia(chat, emptyList(), null, null, null, 50u).map { it.mediaId }

    private fun record(id: String, at: Long, kind: String? = null, payload: ByteArray? = null, text: String = "") =
        MessageRecord(id, chat, text, isSentByMe = false, timestampMs = at, deliveryStatus = DeliveryStatus.DELIVERED,
            mediaType = kind, mediaPayload = payload, orderKey = "k$at")

    /**
     * A message written one at a time or in a batch is in the chat's media at once; a body whose
     * media is replaced indexes the new media, and an edit its new body. Mutations: drop the index
     * in `insert`, in `insertAll`, in `setMedia`, or in `edit` — each reddens.
     */
    @Test
    fun writingAMessageIndexesItsMedia() = runTest {
        assertTrue(messages.insert(record("m1", 1, MediaWire.KIND_ALBUM, album(item("p1", "image/jpeg")).toByteArray())))
        assertEquals(listOf("p1"), gallery())

        val voice = VoiceMessage.newBuilder().setCodec("audio/m4a|v2|1").build().toByteArray()
        assertEquals(2, messages.insertAll(listOf(record("m2", 2, MediaWire.KIND_VOICE, voice), record("m3", 3, text = "hi"))))
        assertEquals(listOf("v2", "p1"), gallery())

        messages.setMedia("m1", MediaWire.KIND_ALBUM, album(item("p1b", "image/jpeg")).toByteArray())
        assertEquals(listOf("v2", "p1b"), gallery())

        messages.edit("m1", "caption", album(item("e1", "image/png")).toByteArray())
        assertEquals(listOf("v2", "e1"), gallery())
    }

    /**
     * What an older build wrote — straight into the store, no index — is indexed by the backfill,
     * and a second run finds nothing to do. Mutations: stop after the first page, or index the
     * listed rows as empty — each reddens.
     */
    @Test
    fun theBackfillIndexesWhatAnOlderBuildWrote() = runTest {
        val bodies = listOf(
            "o1" to content(MessageContent.newBuilder().setMediaAlbum(album(item("a1", "image/heic"))).build()),
            "o2" to content(MessageContent.newBuilder().setMedia(item("a2", "video/mp4")).build()),
            "o3" to voiceBody("a3", 3),
        )
        bodies.forEachIndexed { i, (id, body) ->
            core.insertMessage(
                LocalMessage(
                    id = id, chatId = chat, fromUserId = peer, toUserId = "me", isSentByMe = false,
                    timestamp = i.toLong(), orderKey = "k$i", body = body, contentType = 0, deliveryStatus = 2,
                    retryCount = 0, suiteId = 0, isEdited = false, editedAt = null, replyToMessageId = null,
                    replyToContent = null, transcriptText = null, transcriptLanguage = null, transcriptGeneratedAt = null,
                ),
                null,
            )
        }
        assertEquals(emptyList<String>(), gallery())

        assertEquals(3, messages.indexUnindexedMedia(pageSize = 2u))
        assertEquals(listOf("a3", "a2", "a1"), gallery())
        assertEquals(0, messages.indexUnindexedMedia(pageSize = 2u))
    }
}
