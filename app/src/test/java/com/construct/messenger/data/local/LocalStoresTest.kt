package com.construct.messenger.data.local

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.construct.messenger.data.local.db.ChatEntity
import com.construct.messenger.data.local.db.ConstructDatabase
import com.construct.messenger.data.model.DeliveryStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uniffi.construct_core.LocalStore

/**
 * Which store answers at launch, on the core's real store in a file and Room in memory. "Room
 * asked" is the switch's whole risk: every case below says whether it may be.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LocalStoresTest {
    @get:Rule val dir = TemporaryFolder()

    private val roomDb = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), ConstructDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val wrap = FakeWrap()
    private val file get() = dir.root.resolve("construct.db")
    private val key by lazy { LocalStoreKey(dir.root.resolve("construct.db.key"), wrap) }
    private val scopes = mutableListOf<CoroutineScope>()
    private var roomAsked = 0
    private var roomDeleted = 0

    @After
    fun close() {
        scopes.forEach { it.cancel() }
        roomDb.close()
    }

    private fun launch(roomExists: Boolean = true, me: String? = ME): LocalStores {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
        return LocalStores(file, key, room = { roomAsked++; roomDb }, roomExists = { roomExists }, deleteRoom = { roomDeleted++ }, myUserId = { me }, scope = scope)
    }

    private fun LocalStores.settled(): LocalStores.State = runBlocking {
        withTimeout(30_000) { state.first { it is LocalStores.State.Ready || it is LocalStores.State.Failed } }
    }

    private suspend fun fillRoom() {
        RoomContactStore(roomDb.userDao()).ensure(ANN)
        RoomChatStore(roomDb.chatDao()).insert(ChatRecord(CHAT, ANN, "hi", 10))
        RoomMessageStore(roomDb.messageDao()).insert(MessageRecord("m1", CHAT, "hi", isSentByMe = true, timestampMs = 10, deliveryStatus = DeliveryStatus.SENT, orderKey = "k1"))
    }

    @Test
    fun aNewInstallStartsOnTheCoreAndNeverMakesRoom() {
        val stores = launch(roomExists = false)
        assertEquals(LocalStores.State.Ready(onCore = true), stores.settled())
        assertEquals(0, roomAsked)
        assertTrue(file.exists())
    }

    @Test
    fun roomsRowsMoveAndTheCoreAnswers() = runBlocking {
        fillRoom()
        val stores = launch()
        assertEquals(LocalStores.State.Ready(onCore = true), stores.settled())
        assertEquals(listOf("hi"), GatedMessageStore(stores).observeChat(CHAT).first().map { it.text })
        // Written now, it lands in the core, not in Room.
        GatedChatStore(stores).setPinned(CHAT, true)
        assertTrue(GatedChatStore(stores).get(CHAT)!!.isPinned)
        assertFalse(RoomChatStore(roomDb.chatDao()).get(CHAT)!!.isPinned)
        assertEquals("Room's file goes once the counts match", 1, roomDeleted)
    }

    @Test
    fun theNextLaunchOpensTheCoreWithoutAskingRoom() = runBlocking {
        fillRoom()
        launch().settled()
        roomAsked = 0
        val again = launch()
        assertEquals(LocalStores.State.Ready(onCore = true), again.settled())
        assertEquals(0, roomAsked)
        assertEquals("hi", GatedMessageStore(again).get("m1")!!.text)
    }

    /** Counts that do not match: Room stays the store, and the next launch starts from nothing. */
    @Test
    fun aMismatchLeavesRoomTheStoreAndErasesTheAttempt() = runBlocking {
        fillRoom()
        roomDb.chatDao().insertIfAbsent(ChatEntity("chat-ann-2", ANN))
        val stores = launch()
        assertEquals(LocalStores.State.Ready(onCore = false), stores.settled())
        GatedChatStore(stores).setPinned(CHAT, true)
        assertTrue(RoomChatStore(roomDb.chatDao()).get(CHAT)!!.isPinned)
        assertFalse("the attempt is erased", file.exists())
        assertEquals(0, roomDeleted)
    }

    /** Signed out with history left by an older build: no messages can be addressed, so Room stays. */
    @Test
    fun signedOutWithMessagesRoomStays() {
        runBlocking { fillRoom() }
        assertEquals(LocalStores.State.Ready(onCore = false), launch(me = null).settled())
    }

    /** A crash during the import leaves rows and no marker; the next attempt starts from an empty store. */
    @Test
    fun anUnfinishedAttemptIsStartedAgainFromNothing() = runBlocking {
        fillRoom()
        val k = key.current()!!
        LocalStore(file.path, k).apply {
            // A contact Room no longer holds: left in place, the contact count would not match.
            LocalStoreFeed(this).let { CoreContactStore(it).ensure(BOB) }
            close()
        }
        val stores = launch()
        assertEquals(LocalStores.State.Ready(onCore = true), stores.settled())
        assertNull(GatedContactStore(stores).get(BOB))
    }

    /** The file is there and the key is not its key: nothing answers, and nothing is made over it. */
    @Test
    fun aStoreThatWillNotOpenAnswersNothing() = runBlocking {
        fillRoom()
        launch().settled()
        val held = file.length()
        wrap.held = false
        roomAsked = 0
        val stores = launch()
        assertEquals(LocalStores.State.Failed(LocalStoreSession.Failure.KeyUnavailable), stores.settled())
        assertNull("a call waits", withTimeoutOrNull(300) { GatedChatStore(stores).get(CHAT) })
        assertEquals(0, roomAsked)
        assertEquals(held, file.length())
    }

    /** No file and no key could be made: nothing ever moved, so Room is still the account's store. */
    @Test
    fun noKeyAndNoFileRoomStays() {
        wrap.failWrap = true
        assertEquals(LocalStores.State.Ready(onCore = false), launch().settled())
    }

    private companion object {
        const val ME = "5f0c1a2b-0000-4000-8000-000000000001"
        const val ANN = "0b6e9f6a-3c1d-4f7e-9a2b-5d8c7e6f1a2b"
        const val BOB = "1c7f0a7b-4d2e-4a8f-8b3c-6e9d8f7a2b3c"
        const val CHAT = "direct:ann"
    }
}
