package com.construct.messenger.data.repository

import android.app.Application
import com.construct.messenger.data.local.ChatRecord
import com.construct.messenger.data.local.CoreChatStore
import com.construct.messenger.data.local.CoreContactStore
import com.construct.messenger.data.local.CoreMessageStore
import com.construct.messenger.data.local.CoreNoteStore
import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.data.local.LocalStoreFeed
import com.construct.messenger.data.local.MessageRecord
import com.construct.messenger.data.local.Note
import com.construct.messenger.data.local.NoteStore
import com.construct.messenger.data.model.DeliveryStatus
import com.construct.messenger.data.model.MessageMedia
import com.construct.messenger.util.ConversationId
import com.construct.messenger.util.MediaWire
import com.google.protobuf.ByteString
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import shared.proto.messaging.v1.Content.MediaAlbumMessage
import shared.proto.messaging.v1.Content.MediaMessage
import uniffi.construct_core.LocalStore

/** What a build before 2026-11-23 kept as a chat with ourselves, on the core's store. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SelfChatToNotesTest {
    private val me = "2d8a1b8c-5e3f-4b9a-9c4d-7fae9a8b3c4d"
    private val peer = "0b6e9f6a-3c1d-4f7e-9a2b-5d8c7e6f1a2b"
    private val core = LocalStore.inMemory(ByteArray(32) { 1 })
    private val feed = LocalStoreFeed(core)
    private val contacts = CoreContactStore(feed, { null })
    private val chats = CoreChatStore(feed, { me })
    private val messages = CoreMessageStore(feed, { me })
    private val notes = CoreNoteStore(feed)
    private val keystore = mock<KeystoreManager>().also { whenever(it.getUserId()).thenReturn(me) }
    private val selfChat = ConversationId.direct(me, me)

    @After
    fun close() = core.close()

    private suspend fun move(into: NoteStore = notes) = SelfChatToNotes(keystore, chats, messages, into).move()

    private suspend fun selfChatWith(vararg rows: MessageRecord) {
        contacts.ensure(me)
        chats.insert(ChatRecord(id = selfChat, peerId = me))
        rows.forEach { assertTrue(messages.insert(it)) }
    }

    /** [key] differs per copy of the same file, as a sent and a received row's bytes may. */
    private fun row(id: String, text: String, sent: Boolean, at: Long, photo: String? = null, key: Int = 1) = MessageRecord(
        id = id, chatId = selfChat, text = text, isSentByMe = sent, timestampMs = at, deliveryStatus = DeliveryStatus.SENT,
        mediaType = if (photo != null) MediaWire.KIND_ALBUM else null,
        mediaPayload = photo?.let {
            MediaAlbumMessage.newBuilder().setCaption(text).addItems(
                MediaMessage.newBuilder().setMediaId(it).setMimeType("image/jpeg").setFileSize(key.toLong())
                    .setEncryptionKey(ByteString.copyFrom(ByteArray(32) { 7 })),
            ).build().toByteArray()
        },
    )

    private suspend fun all() = notes.observe(50).first()

    /**
     * Each thing written is one note, at its time, and the copy that came back is none; what was
     * sent from another device and has no row of ours is kept. The chat is gone, and other chats
     * are not. Mutations: keep the received copies; drop them all; leave the chat — each reddens.
     */
    @Test
    fun `what one wrote to oneself becomes notes, once each, and the chat goes`() = runTest {
        selfChatWith(
            row("a1", "milk", sent = true, at = 1_000), row("a2", "milk", sent = false, at = 1_001),
            row("b1", "milk", sent = true, at = 2_000), row("b2", "milk", sent = false, at = 2_001),
            row("c2", "from the desktop", sent = false, at = 3_000),
            row("e1", " ", sent = true, at = 4_000),
        )
        contacts.ensure(peer)
        chats.insert(ChatRecord(id = "chat-peer", peerId = peer))

        move()

        assertEquals(listOf("c2" to 3_000L, "b1" to 2_000L, "a1" to 1_000L), all().map { it.id to it.createdAtMs })
        assertNull(chats.get(selfChat))
        assertNull(messages.get("a1"))
        assertNotNull(chats.get("chat-peer"))
        // Our own contact row stays: the core deletes a contact's devices with it, and these are ours.
        assertNotNull(contacts.get(me))
        move()
        assertEquals(3, all().size)
    }

    /**
     * A photo moves with its caption, its copy does not, and another photo of the same caption is
     * another note. Mutations: compare words only; compare the media's bytes.
     */
    @Test
    fun `media moves too, once each`() = runTest {
        selfChatWith(
            row("p1", "cat", sent = true, at = 1_000, photo = "m1"), row("p2", "cat", sent = false, at = 1_001, photo = "m1", key = 2),
            row("p3", "cat", sent = false, at = 500, photo = "m2"),
        )
        move()
        assertEquals(
            listOf("p1" to "m1", "p3" to "m2"),
            all().map { it.id to (it.media as MessageMedia.Album).items.single().mediaId },
        )
        assertEquals(setOf("m1", "m2"), notes.heldMediaIds())
        assertEquals("cat", all().first().text)
    }

    /** Nothing is deleted before every note is in, and a second run adds none twice. Mutation: delete the chat first. */
    @Test
    fun `a move cut off keeps the chat, and the next finishes it`() = runTest {
        selfChatWith(row("a1", "milk", sent = true, at = 1_000), row("b1", "bread", sent = true, at = 2_000))
        move(object : NoteStore by notes {
            override suspend fun insert(note: Note) = if (note.id == "b1") error("full") else notes.insert(note)
        })
        assertNotNull(messages.get("a1"))
        assertNotNull(chats.get(selfChat))
        move()
        assertEquals(listOf("bread", "milk"), all().map { it.text })
        assertNull(chats.get(selfChat))
    }

    /** Remove [SelfChatToNotes], its test and the line in `KonstructApp` after this day. */
    @Test
    fun `the move is not past its last day`() {
        assertFalse(LocalDate.now().isAfter(LocalDate.parse(SelfChatToNotes.LAST_DAY)))
    }
}
