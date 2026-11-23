package com.construct.messenger.data.local

import android.app.Application
import com.construct.messenger.data.model.DeliveryStatus
import com.construct.messenger.data.model.MessageMedia
import com.construct.messenger.util.MediaWire
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import shared.proto.messaging.v1.Content.MediaAlbumMessage
import shared.proto.messaging.v1.Content.MediaMessage
import uniffi.construct_core.LocalStore

/** Notes on the core's tables (vault todo 279). **Canon:** iOS `NotesStoreTests`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NoteStoreTest {
    private val core = LocalStore.inMemory(ByteArray(32) { 1 })
    private val feed = LocalStoreFeed(core)
    private val notes = CoreNoteStore(feed)

    @After
    fun close() = core.close()

    private fun album(id: String, caption: String) = MediaAlbumMessage.newBuilder().setCaption(caption).addItems(
        MediaMessage.newBuilder().setMediaId(id).setMimeType("image/jpeg").setFileSize(10)
            .setEncryptionKey(com.google.protobuf.ByteString.copyFrom(ByteArray(32) { 7 })),
    ).build().toByteArray()

    private fun row(id: String, mediaId: String, caption: String = "") = MessageRecord(
        id = id, chatId = "c", text = caption, isSentByMe = false, timestampMs = 5, deliveryStatus = DeliveryStatus.DELIVERED,
        mediaType = MediaWire.KIND_ALBUM, mediaPayload = album(mediaId, caption), orderKey = "k$id",
    )

    private fun photoNote(id: String, mediaId: String, caption: String = "") =
        Note(id = id, body = LocalMessagePayload.encode(row(id, mediaId, caption)), createdAtMs = 5)

    private suspend fun ids() = notes.observe(50).first().map { it.id }

    /** Mutation: swap two fields in `note()` or `local()`; drop one. */
    @Test
    fun `a note is read back as written`() = runTest {
        val note = Note.ofText("words", "n1", 10).copy(editedAtMs = null, isPinned = true, sourceName = "Ann", sourceAtMs = 3)
        assertTrue(notes.insert(note))
        assertEquals(note, notes.get("n1"))
        assertEquals("words", notes.get("n1")!!.text)
        assertFalse(notes.insert(note.copy(sourceName = "other")))
        assertEquals("Ann", notes.get("n1")!!.sourceName)
    }

    /** A note's body is a message body: its photo and its caption are read as a bubble reads them. */
    @Test
    fun `a photo note reads as its message did`() = runTest {
        notes.insert(photoNote("p", "m1", "the caption"))
        val note = notes.get("p")!!
        assertEquals("the caption", note.text)
        assertEquals(listOf("m1"), (note.media as MessageMedia.Album).items.map { it.mediaId })
        assertFalse(note.isEditable)
        assertTrue(Note.ofText("x").isEditable)
    }

    /** Mutation: drop the words in `insert` or `edit`. */
    @Test
    fun `pinned first then newest, and search reads words, captions and edits`() = runTest {
        notes.insert(Note.ofText("apple pie", "a", 1))
        notes.insert(Note.ofText("banana", "b", 2))
        notes.insert(photoNote("p", "m1", "an apple tree"))
        notes.setPinned("a", true)
        assertEquals(listOf("a", "p", "b"), ids())
        assertEquals(setOf("a", "p"), notes.search("apple", 50).first().map { it.id }.toSet())
        notes.edit("b", Note.textBody("apple now"), 9)
        assertEquals(9L, notes.get("b")!!.editedAtMs)
        assertEquals(setOf("a", "p", "b"), notes.search("apple", 50).first().map { it.id }.toSet())
        assertNull(notes.edit("none", Note.textBody("x"), 9))
        assertTrue(notes.setPinned("b", true))
        assertFalse(notes.setPinned("none", true))
    }

    /**
     * The files a note holds are named, and a delete answers those nothing else holds: not one a
     * second note has, not one a chat still shows. Mutation: drop the media in `insert`; drop
     * `heldByNoMessage`.
     */
    @Test
    fun `a delete releases only what nothing else holds`() = runTest {
        CoreContactStore(feed, { null }).ensure(PEER)
        CoreChatStore(feed, { null }).insert(ChatRecord(id = "c", peerId = PEER))
        assertTrue(CoreMessageStore(feed, { "me" }).insert(row("msg", "in-chat")))
        notes.insert(photoNote("p1", "alone"))
        notes.insert(photoNote("p2", "shared"))
        notes.insert(photoNote("p3", "shared"))
        notes.insert(photoNote("p4", "in-chat"))
        assertEquals(setOf("alone", "shared", "in-chat"), notes.heldMediaIds())
        assertEquals(listOf("alone"), notes.delete(listOf("p1", "p2", "p4")))
        assertEquals(listOf("shared"), notes.delete(listOf("p3")))
        assertEquals(emptyList<String>(), ids())
        assertEquals(emptyList<String>(), notes.delete(listOf("p3")))
    }
}

private const val PEER = "0b6e9f6a-3c1d-4f7e-9a2b-5d8c7e6f1a2b"
