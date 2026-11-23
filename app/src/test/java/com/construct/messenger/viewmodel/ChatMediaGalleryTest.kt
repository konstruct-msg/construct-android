package com.construct.messenger.viewmodel

import com.construct.messenger.data.local.ChatMediaItem
import com.construct.messenger.data.local.CoreChatStore
import com.construct.messenger.data.local.CoreContactStore
import com.construct.messenger.data.local.CoreMessageStore
import com.construct.messenger.data.local.LocalStoreFeed
import com.construct.messenger.data.local.MessageRecord
import com.construct.messenger.data.local.ensure
import com.construct.messenger.data.model.DeliveryStatus
import com.construct.messenger.util.MediaWire
import com.google.protobuf.ByteString
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import shared.proto.messaging.v1.Content.MediaAlbumMessage
import shared.proto.messaging.v1.Content.MediaMessage
import shared.proto.messaging.v1.Content.MediaPresentation
import shared.proto.messaging.v1.Content.VoiceMessage
import uniffi.construct_core.LocalMediaKind
import uniffi.construct_core.LocalStore

/**
 * The chat gallery (vault todo 327) on the core's store, so the index is the one writes fill — the
 * same claims as iOS `ChatMediaGalleryModelTests`. Each test names the mutation that must redden it.
 */
class ChatMediaGalleryTest {
    private val core = LocalStore.inMemory(ByteArray(32) { 1 })
    private val feed = LocalStoreFeed(core)
    private val store = CoreMessageStore(feed, myUserId = { "me" })
    private val peer = "0b6e9f6a-3c1d-4f7e-9a2b-5d8c7e6f1a2b"
    private val chat = "chat-0b6e9f6a"

    @Before
    fun openChat() = runTest {
        CoreContactStore(feed, { null }).ensure(peer)
        CoreChatStore(feed, { null }).ensure(chat, peer)
    }

    @After
    fun close() = core.close()

    private fun item(id: String, mime: String, videoNote: Boolean = false, key: Int = 32): MediaMessage =
        MediaMessage.newBuilder().setMediaId(id).setMimeType(mime).setEncryptionKey(ByteString.copyFrom(ByteArray(key) { 7 }))
            .apply { if (videoNote) presentation = MediaPresentation.MEDIA_PRESENTATION_VIDEO_NOTE }.build()

    private fun albumRecord(id: String, second: Long, vararg items: MediaMessage) = MessageRecord(
        id, chat, "", isSentByMe = false, timestampMs = second * 1000, deliveryStatus = DeliveryStatus.DELIVERED,
        mediaType = MediaWire.KIND_ALBUM, mediaPayload = MediaAlbumMessage.newBuilder().addAllItems(items.toList()).build().toByteArray(),
        orderKey = "k%04d".format(second),
    )

    private suspend fun write(id: String, second: Long, vararg items: MediaMessage) {
        assertTrue(store.insert(albumRecord(id, second, *items)))
    }

    private fun gallery(pageSize: Int = 90) =
        ChatMediaGallery(page = { kinds, before, limit -> store.media(chat, kinds, before, limit) }, pageSize = pageSize)

    /**
     * Pictures, videos and animations in the grid; files in the list; voice notes and round videos
     * in neither. Mutations: the grid asks for files, or the list for voice notes — each reddens.
     */
    @Test
    fun eachTabShowsItsOwnKinds() = runTest {
        write("m1", 1, item("p", "image/jpeg"), item("v", "video/mp4"), item("g", "image/gif"))
        write("m2", 2, item("d", "application/pdf"))
        write("m3", 3, item("n", "video/mp4", videoNote = true))
        assertTrue(
            store.insert(
                MessageRecord(
                    "m4", chat, "", isSentByMe = false, timestampMs = 4000, deliveryStatus = DeliveryStatus.DELIVERED,
                    mediaType = MediaWire.KIND_VOICE, mediaPayload = VoiceMessage.newBuilder().setCodec("audio/m4a|a|1").build().toByteArray(),
                    orderKey = "k0004",
                ),
            ),
        )

        val grid = gallery()
        grid.loadMore()
        assertEquals(listOf("g", "v", "p"), grid.items.map { it.mediaId })

        val list = gallery()
        list.select(ChatMediaTab.FILES)
        assertEquals(listOf("d"), list.items.map { it.mediaId })
    }

    /**
     * Page after page from where the last stopped, until one comes back short; nothing read twice
     * and nothing after the end. Mutations: page from the start each time, call a full page the
     * end, or drop the item from the store's cursor — each reddens.
     */
    @Test
    fun pagesContinueUntilAShortOne() = runTest {
        for (s in 1L..5L) write("m$s", s, item("p$s", "image/png"))
        val g = gallery(pageSize = 2)

        g.loadMore()
        g.loadMore()
        assertEquals(listOf("p5", "p4", "p3", "p2"), g.items.map { it.mediaId })
        assertFalse(g.isComplete)
        g.loadMore()
        assertEquals(listOf("p5", "p4", "p3", "p2", "p1"), g.items.map { it.mediaId })
        assertTrue(g.isComplete)
        g.loadMore()
        assertEquals(5, g.items.size)
    }

    /** An album a page cuts continues with the rest of it. Mutation: no item in the cursor — the album's rest is lost or repeated. */
    @Test
    fun anAlbumCutByAPageContinues() = runTest {
        write("m1", 1, item("a", "image/png"), item("b", "image/png"), item("c", "image/png"))
        val g = gallery(pageSize = 2)
        g.loadMore()
        g.loadMore()
        assertEquals(listOf("c", "b", "a"), g.items.map { it.mediaId })
    }

    /** The next page is read once the grid nears the end of what it holds, not before. Mutation: the distance check reversed. */
    @Test
    fun theNextPageIsReadNearTheEnd() = runTest {
        for (s in 1L..40L) write("m$s", s, item("p$s", "image/png"))
        val g = gallery(pageSize = 20)
        g.loadMore()

        assertFalse(g.loadMoreIfNeeded(0))
        assertEquals(20, g.items.size)
        assertTrue(g.loadMoreIfNeeded(20 - ChatMediaGallery.PREFETCH_DISTANCE))
        assertEquals(40, g.items.size)
    }

    /**
     * A message of two files is one row of the list, the newest message first; another tab drops
     * what was loaded, the same tab again is not a reload. Mutations: drop the de-duplication, or
     * keep the old items on a switch — each reddens.
     */
    @Test
    fun filesAreOneRowPerMessageAndASwitchStartsOver() = runTest {
        write("m1", 1, item("d1", "application/pdf"), item("d2", "application/zip"))
        write("m2", 2, item("d3", "text/plain"))
        write("m3", 3, item("p", "image/jpeg"))
        val g = gallery()
        g.loadMore()
        assertEquals(listOf("p"), g.items.map { it.mediaId })

        assertTrue(g.select(ChatMediaTab.FILES))
        assertEquals(listOf("d3", "d2", "d1"), g.items.map { it.mediaId })
        assertEquals(listOf("m2", "m1"), g.messageIds)
        assertFalse(g.select(ChatMediaTab.FILES))
        assertEquals(3, g.items.size)
    }

    /**
     * A tile is the item its media id names, though the decoder dropped an unreadable one before
     * it. Mutation: look the item up by position — the tile shows the wrong picture, or none.
     */
    @Test
    fun anItemIsFoundByItsMediaIdNotItsPosition() {
        val record = albumRecord("m1", 1, item("a", "image/png"), item("b", "image/png", key = 0), item("c", "image/png"))
        fun entry(item: Int, id: String) = ChatMediaItem("m1", item, id, LocalMediaKind.PHOTO, 1, 1000, false, "k0001")

        assertEquals("c", mediaIn(record, entry(2, "c"))?.mediaId)
        assertEquals("a", mediaIn(record, entry(0, "a"))?.mediaId)
        assertNull(mediaIn(record, entry(1, "b")))
        assertNull(mediaIn(null, entry(0, "a")))
    }
}
