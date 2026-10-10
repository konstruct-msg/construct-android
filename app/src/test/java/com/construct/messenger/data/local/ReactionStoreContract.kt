package com.construct.messenger.data.local

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.construct.messenger.data.local.db.ConstructDatabase
import com.construct.messenger.data.model.DeliveryStatus
import com.construct.messenger.util.JdkEmoji
import com.construct.messenger.util.ReactionRules
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uniffi.construct_core.LocalStore

/**
 * What every [ReactionStore] does — Room's today and the core's `LocalStore` (TODO 136), which
 * replaces it. Reactions sit beside messages, never joined to them: a chat and its messages are
 * set up first, and a reaction may name a message that is not here.
 */
abstract class ReactionStoreContract {
    protected abstract val contacts: ContactStore
    protected abstract val chats: ChatStore
    protected abstract val messages: MessageStore
    protected abstract val store: ReactionStore

    private val peer = "0b6e9f6a-3c1d-4f7e-9a2b-5d8c7e6f1a2b"
    private val other = "1c7f0a7b-4d2e-4a8f-8b3c-6e9d8f7a2b3c"
    private val chat = "chat-0b6e9f6a"
    private val otherChat = "chat-1c7f0a7b"
    private val m1 = "aaaaaaaa-0000-4000-8000-000000000001"
    private val m2 = "aaaaaaaa-0000-4000-8000-000000000002"
    private val elsewhere = "bbbbbbbb-0000-4000-8000-000000000001"
    private val missing = "cccccccc-0000-4000-8000-000000000001"

    @Before
    fun openChats() = runTest {
        contacts.ensure(peer)
        contacts.ensure(other)
        chats.ensure(chat, peer)
        chats.ensure(otherChat, other)
        message(chat, m1, 1)
        message(chat, m2, 2)
        message(otherChat, elsewhere, 3)
    }

    private suspend fun message(chatId: String, id: String, at: Long) {
        messages.insert(MessageRecord(id, chatId, "m", isSentByMe = false, timestampMs = at, deliveryStatus = DeliveryStatus.DELIVERED))
    }

    private fun reaction(target: String, reactor: String = peer, emoji: String = "👍", at: Long = 10, received: Long = 100) =
        ReactionRecord(target, reactor, emoji, at, received)

    @Test
    fun aReactorHasOneReactionPerMessage() = runTest {
        store.put(reaction(m1, emoji = "👍", at = 10))
        store.put(reaction(m1, emoji = "😂", at = 20))
        store.put(reaction(m1, reactor = other, emoji = "🔥"))
        assertEquals("😂", store.get(m1, peer)!!.emoji)
        assertEquals(20L, store.get(m1, peer)!!.timestampMs)
        assertEquals("🔥", store.get(m1, other)!!.emoji)

        store.delete(m1, peer)
        assertNull(store.get(m1, peer))
        assertEquals("🔥", store.get(m1, other)!!.emoji)
    }

    @Test
    fun aChatShowsTheReactionsOnItsOwnMessagesOldestFirst() = runTest {
        store.put(reaction(m2, at = 30, emoji = "b"))
        store.put(reaction(m1, at = 20, emoji = "a"))
        store.put(reaction(elsewhere, at = 10, emoji = "elsewhere"))
        store.put(reaction(missing, at = 5, emoji = "orphan"))
        assertEquals(listOf("a", "b"), store.observeChat(chat).first().map { it.emoji })
    }

    /** An orphan waits for its message, and shows once the message is here. */
    @Test
    fun anOrphanShowsWhenItsMessageArrives() = runTest {
        store.put(reaction(missing, emoji = "early"))
        assertTrue(store.observeChat(chat).first().isEmpty())
        message(chat, missing, 4)
        assertEquals(listOf("early"), store.observeChat(chat).first().map { it.emoji })
    }

    /**
     * Only orphans are swept, and only old ones. Mutation: on the core, `expire_reactions` in
     * place of the walk — the old reaction on a message that is here goes too.
     */
    @Test
    fun theSweepForgetsOldOrphansOnly() = runTest {
        store.put(reaction(missing, received = 100))
        store.put(reaction(m1, received = 100))
        store.put(reaction(missing, reactor = other, received = 300))

        store.deleteOrphansBefore(200)

        assertNull(store.get(missing, peer))
        assertEquals("👍", store.get(m1, peer)!!.emoji)
        assertEquals("👍", store.get(missing, other)!!.emoji)
    }

    /** The rules over the store: an older clock does not replace a newer reaction; a removal clears. */
    @Test
    fun theRulesApplyOverIt() = runTest {
        store.applyIncoming(m1.uppercase(), peer.uppercase(), 1, "😂", 20, 20, nowMs = 1000, props = JdkEmoji)
        val kept = store.applyIncoming(m1, peer, 1, "👍", 10, 10, nowMs = 1000, props = JdkEmoji)
        assertEquals(ReactionRules.Decision.KeepExisting, kept)
        assertEquals("😂", store.get(m1, peer)!!.emoji)

        store.applyIncoming(m1, peer, 2, "", 30, 30, nowMs = 1000)
        assertNull(store.get(m1, peer))

        store.restoreLocal(m1, peer, ReactionRules.Row("🔥", 5), nowMs = 1000)
        assertEquals(ReactionRules.Row("🔥", 5), store.current(m1, peer))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RoomReactionStoreTest : ReactionStoreContract() {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), ConstructDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    override val contacts: ContactStore = RoomContactStore(db.userDao())
    override val chats: ChatStore = RoomChatStore(db.chatDao())
    override val messages: MessageStore = RoomMessageStore(db.messageDao())
    override val store: ReactionStore = RoomReactionStore(db.reactionDao())

    @After
    fun close() = db.close()
}

class FakeReactionStoreTest : ReactionStoreContract() {
    override val contacts: ContactStore = FakeContactStore()
    override val chats: ChatStore = FakeChatStore()
    override val messages: MessageStore = FakeMessageStore()
    override val store: ReactionStore = FakeReactionStore(messages)
}

/** The same cases on the core's encrypted store — the host build of the library the app ships. */
class CoreReactionStoreTest : ReactionStoreContract() {
    private val core = LocalStore.inMemory(ByteArray(32) { 1 })
    private val feed = LocalStoreFeed(core)
    override val contacts: ContactStore = CoreContactStore(feed)
    override val chats: ChatStore = CoreChatStore(feed)
    override val messages: MessageStore = CoreMessageStore(feed, myUserId = { "me" })
    override val store: ReactionStore = CoreReactionStore(feed)

    @After
    fun close() = core.close()
}
