package com.construct.messenger.data.repository

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.construct.messenger.data.local.ChatRecord
import com.construct.messenger.data.local.CoreChatStore
import com.construct.messenger.data.local.CoreContactStore
import com.construct.messenger.data.local.CoreMessageStore
import com.construct.messenger.data.local.CoreSessionBlobStore
import com.construct.messenger.data.local.FakeSessionBlobStore
import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.data.local.LocalStoreFeed
import com.construct.messenger.data.local.MessageRecord
import com.construct.messenger.data.local.SessionBlobStore
import com.construct.messenger.data.model.DeliveryStatus
import com.construct.messenger.util.ConversationId
import com.construct.messenger.util.MediaWire
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
import uniffi.construct_core.LocalStore

/** What a build before 2026-11-23 kept as a chat with ourselves, on the core's store. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SelfChatToDraftsTest {
    private val me = "2d8a1b8c-5e3f-4b9a-9c4d-7fae9a8b3c4d"
    private val peer = "0b6e9f6a-3c1d-4f7e-9a2b-5d8c7e6f1a2b"
    private val core = LocalStore.inMemory(ByteArray(32) { 1 })
    private val feed = LocalStoreFeed(core)
    private val contacts = CoreContactStore(feed, { null })
    private val chats = CoreChatStore(feed, { me })
    private val messages = CoreMessageStore(feed, { me })
    private val keystore = mock<KeystoreManager>().also { whenever(it.getUserId()).thenReturn(me) }
    private val selfChat = ConversationId.direct(me, me)

    @After
    fun close() = core.close()

    private fun TestScope.drafts(store: SessionBlobStore = CoreSessionBlobStore(core)) = DraftsRepository(
        ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("selfchat", Context.MODE_PRIVATE),
        store,
        TestScope(UnconfinedTestDispatcher(testScheduler)),
    )

    private suspend fun selfChatWith(vararg rows: MessageRecord) {
        contacts.ensure(me)
        chats.insert(ChatRecord(id = selfChat, peerId = me))
        rows.forEach { assertTrue(messages.insert(it)) }
    }

    private fun row(id: String, text: String, sent: Boolean, at: Long, media: Boolean = false) = MessageRecord(
        id = id, chatId = selfChat, text = text, isSentByMe = sent, timestampMs = at, deliveryStatus = DeliveryStatus.SENT,
        mediaType = if (media) MediaWire.KIND_ALBUM else null,
        mediaPayload = if (media) shared.proto.messaging.v1.Content.MediaAlbumMessage.getDefaultInstance().toByteArray() else null,
    )

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
        )
        contacts.ensure(peer)
        chats.insert(ChatRecord(id = "chat-peer", peerId = peer))
        val drafts = drafts().also { it.ready.join() }

        SelfChatToDrafts(keystore, chats, messages, drafts).move()

        assertEquals(
            listOf("c2" to 3_000L, "b1" to 2_000L, "a1" to 1_000L),
            drafts.drafts.value.map { it.id to it.createdAt },
        )
        assertNull(chats.get(selfChat))
        assertNull(messages.get("a1"))
        assertNotNull(chats.get("chat-peer"))
        // Our own contact row stays: the core deletes a contact's devices with it, and these are ours.
        assertNotNull(contacts.get(me))

        val again = drafts().also { it.ready.join() }
        SelfChatToDrafts(keystore, chats, messages, again).move()
        assertEquals(3, again.drafts.value.size)
    }

    /** A move cut off before the chat was deleted adds nothing the second time. Mutation: adopt by new ids. */
    @Test
    fun `a move that runs again adds nothing twice, among notes already there`() = runTest {
        selfChatWith(row("a1", "milk", sent = true, at = 1_000))
        val drafts = drafts().also { it.ready.join() }
        drafts.add("already a note")
        val note = SelfChatToDrafts.notesOf(messages.observeChat(selfChat).first())
        assertTrue(drafts.adopt(note))
        SelfChatToDrafts(keystore, chats, messages, drafts).move()
        assertEquals(listOf("already a note", "milk"), drafts.drafts.value.map { it.text })
    }

    /** A photo has no place in a note yet: its row stays. Mutation: delete the whole chat. */
    @Test
    fun `media rows are kept, text rows move`() = runTest {
        selfChatWith(row("a1", "milk", sent = true, at = 1_000), row("p1", "caption", sent = true, at = 2_000, media = true))
        val drafts = drafts().also { it.ready.join() }
        SelfChatToDrafts(keystore, chats, messages, drafts).move()
        assertEquals(listOf("milk"), drafts.drafts.value.map { it.text })
        assertNull(messages.get("a1"))
        assertNotNull(messages.get("p1"))
        assertNotNull(chats.get(selfChat))
    }

    /** Nothing is deleted before the notes are stored. Mutation: ignore what `adopt` answers. */
    @Test
    fun `when the notes cannot be stored the chat stays`() = runTest {
        selfChatWith(row("a1", "milk", sent = true, at = 1_000))
        val inner = FakeSessionBlobStore()
        val failing = object : SessionBlobStore by inner {
            override suspend fun put(key: String, bytes: ByteArray) = error("full")
        }
        val drafts = drafts(failing).also { it.ready.join() }
        SelfChatToDrafts(keystore, chats, messages, drafts).move()
        assertNotNull(messages.get("a1"))
        assertNotNull(chats.get(selfChat))
    }

    /** Remove [SelfChatToDrafts], its test and the line in `KonstructApp` after this day. */
    @Test
    fun `the move is not past its last day`() {
        assertFalse(LocalDate.now().isAfter(LocalDate.parse(SelfChatToDrafts.LAST_DAY)))
    }
}
