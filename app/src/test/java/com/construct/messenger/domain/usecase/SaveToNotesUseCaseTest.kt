package com.construct.messenger.domain.usecase

import android.app.Application
import com.construct.messenger.data.local.ChatRecord
import com.construct.messenger.data.local.CoreChatStore
import com.construct.messenger.data.local.CoreContactStore
import com.construct.messenger.data.local.CoreMessageStore
import com.construct.messenger.data.local.CoreNoteStore
import com.construct.messenger.data.local.LocalStoreFeed
import com.construct.messenger.data.local.MessageRecord
import com.construct.messenger.data.local.Note
import com.construct.messenger.data.local.NoteStore
import com.construct.messenger.data.model.DeliveryStatus
import com.construct.messenger.data.model.MediaItem
import com.construct.messenger.data.model.Message
import com.construct.messenger.data.model.MessageMedia
import com.construct.messenger.data.repository.MediaRepository
import com.construct.messenger.domain.usecase.SaveToNotesUseCase.Outcome
import com.construct.messenger.util.MediaWire
import com.google.protobuf.ByteString
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import shared.proto.messaging.v1.Content.MediaAlbumMessage
import shared.proto.messaging.v1.Content.MediaMessage
import uniffi.construct_core.LocalStore

/** "Save to notes" on a message. **Canon:** iOS `NoteSaver`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SaveToNotesUseCaseTest {
    private val core = LocalStore.inMemory(ByteArray(32) { 1 })
    private val feed = LocalStoreFeed(core)
    private val messages = CoreMessageStore(feed, { "me" })
    private val notes = CoreNoteStore(feed)
    private val fetched = mutableListOf<String>()
    private var gone = emptySet<String>()
    /** The notes there were as each file was asked for. */
    private val notesWhenFetched = mutableListOf<Int>()
    private val media = mock<MediaRepository>().also { repository ->
        repository.stub {
            onBlocking { prefetch(any()) } doSuspendableAnswer { call ->
                val item = call.getArgument<MediaItem>(0)
                notesWhenFetched += notes.observe(50).first().size
                if (item.mediaId in gone) error("gone")
                fetched += item.mediaId
            }
        }
    }
    private val save = SaveToNotesUseCase(messages, notes, media)

    @After
    fun close() = core.close()

    private suspend fun message(id: String, text: String, sent: Boolean, vararg photos: String) {
        CoreContactStore(feed, { null }).ensure(PEER)
        CoreChatStore(feed, { null }).insert(ChatRecord(id = "c", peerId = PEER))
        assertTrue(
            messages.insert(
                MessageRecord(
                    id = id, chatId = "c", text = text, isSentByMe = sent, timestampMs = 77, deliveryStatus = DeliveryStatus.DELIVERED,
                    mediaType = if (photos.isEmpty()) null else MediaWire.KIND_ALBUM,
                    mediaPayload = if (photos.isEmpty()) null else album(*photos, caption = text), orderKey = "k$id",
                ),
            ),
        )
    }

    private fun album(vararg ids: String, caption: String = ""): ByteArray = MediaAlbumMessage.newBuilder().setCaption(caption).addAllItems(
        ids.map {
            MediaMessage.newBuilder().setMediaId(it).setMimeType("image/jpeg")
                .setEncryptionKey(ByteString.copyFrom(ByteArray(32) { 7 })).build()
        },
    ).build().toByteArray()

    private suspend fun only() = notes.observe(50).first().single()

    /** Whose and when for another's words; only when for one's own. Mutation: keep the name for both; now for the time. */
    @Test
    fun `a note says whose words they were and when`() = runTest {
        message("in", "hello", sent = false)
        assertEquals(Outcome.SAVED, save("in", "Ann", nowMs = 500))
        with(only()) {
            assertEquals("hello", text)
            assertEquals("Ann" to 77L, sourceName to sourceAtMs)
            assertEquals(500L, createdAtMs)
        }
        notes.delete(listOf(only().id))
        message("out", "mine", sent = true)
        save("out", "Ann")
        assertNull(only().sourceName)
        assertEquals(77L, only().sourceAtMs)
    }

    /**
     * The note is in before any file is asked for, every file is asked for whatever the others
     * answered, and one that is gone is said. Mutations: insert after; stop at the first failure;
     * answer SAVED.
     */
    @Test
    fun `files are fetched after the note is in, each one`() = runTest {
        message("p", "cap", sent = false, "m1", "m2", "m3")
        gone = setOf("m1")
        assertEquals(Outcome.SAVED_WITHOUT_FILES, save("p", "Ann"))
        assertEquals(listOf("m2", "m3"), fetched)
        assertEquals(listOf(1, 1, 1), notesWhenFetched)
        assertEquals(setOf("m1", "m2", "m3"), notes.heldMediaIds())
        assertEquals("cap", only().text)
    }

    /** Mutation: make a note of a message that is gone, or of one still uploading. */
    @Test
    fun `nothing to keep, or nowhere to keep it, is a failure`() = runTest {
        assertEquals(Outcome.FAILED, save("none", "Ann"))
        message("up", "", sent = true, "local-1")
        assertEquals(Outcome.FAILED, save("up", "Ann"))
        assertEquals(emptyList<Note>(), notes.observe(50).first())
        message("in", "hello", sent = false, "m1")
        val full = object : NoteStore by notes {
            override suspend fun insert(note: Note): Boolean = error("full")
        }
        assertEquals(Outcome.FAILED, SaveToNotesUseCase(messages, full, media)("in", "Ann"))
        assertEquals(emptyList<String>(), fetched)
    }

    /** Mutation: offer it on a message still uploading, or with nothing in it. */
    @Test
    fun `it is offered where there is something to keep`() {
        fun message(body: String, media: MessageMedia?) =
            Message(id = "m", chatId = "c", body = body, isOutgoing = true, timestamp = 1, deliveryStatus = DeliveryStatus.SENT, media = media)
        assertTrue(SaveToNotesUseCase.keeps(message("hi", null)))
        assertFalse(SaveToNotesUseCase.keeps(message(" ", null)))
        assertTrue(SaveToNotesUseCase.keeps(message("", MediaWire.decode(MediaWire.KIND_ALBUM, album("m1")))))
        assertFalse(SaveToNotesUseCase.keeps(message("", MediaWire.decode(MediaWire.KIND_ALBUM, album("local-1")))))
    }
}

private const val PEER = "0b6e9f6a-3c1d-4f7e-9a2b-5d8c7e6f1a2b"
