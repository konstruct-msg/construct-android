package com.construct.messenger.data.local

import android.app.Application
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

/** The core's store at launch, real and in a file (TODO 136); Room is gone and leaves only its file. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LocalStoresTest {
    @get:Rule val dir = TemporaryFolder()

    private val wrap = FakeWrap()
    private val file get() = dir.root.resolve("construct.db")
    private val key by lazy { LocalStoreKey(dir.root.resolve("construct.db.key"), wrap) }
    private val scopes = mutableListOf<CoroutineScope>()
    private var roomFileLeft = false
    private var leftoversDeleted = 0

    @After
    fun close() {
        scopes.forEach { it.cancel() }
    }

    private fun launch(): LocalStores {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
        return LocalStores(
            file,
            key,
            deleteRoomLeftovers = { leftoversDeleted++; roomFileLeft.also { roomFileLeft = false } },
            myUserId = { ME },
            scope = scope,
        )
    }

    private fun LocalStores.settled(): LocalStores.State = runBlocking {
        withTimeout(30_000) { state.first { it is LocalStores.State.Ready || it is LocalStores.State.Failed } }
    }

    @Test
    fun aNewInstallOpensTheCore() {
        assertEquals(LocalStores.State.Ready, launch().settled())
        assertTrue(file.exists())
    }

    @Test
    fun whatIsWrittenIsThereNextLaunch() = runBlocking {
        val first = launch()
        first.settled()
        GatedContactStore(first).ensure(ANN)
        GatedChatStore(first).insert(ChatRecord(CHAT, ANN, "hi", 10))

        val again = launch()
        assertEquals(LocalStores.State.Ready, again.settled())
        assertEquals("hi", GatedChatStore(again).get(CHAT)!!.lastMessageText)
    }

    /**
     * Room's file from a build that never moved it — the transcript in the clear — goes once the
     * core's store is open. Mutation: drop the call — reddens.
     */
    @Test
    fun roomsLeftoverFileIsDeletedOnceTheStoreOpens() {
        roomFileLeft = true
        assertEquals(LocalStores.State.Ready, launch().settled())
        assertEquals(1, leftoversDeleted)
        assertFalse(roomFileLeft)
    }

    /** The file is there and the key is not its key: nothing answers, and nothing is made over it. */
    @Test
    fun aStoreThatWillNotOpenAnswersNothing() = runBlocking {
        launch().settled()
        val held = file.length()
        wrap.held = false
        leftoversDeleted = 0
        val stores = launch()
        assertEquals(LocalStores.State.Failed(LocalStoreSession.Failure.KeyUnavailable), stores.settled())
        assertNull("a call waits", withTimeoutOrNull(300) { GatedChatStore(stores).get(CHAT) })
        assertEquals(held, file.length())
        assertEquals("nothing is deleted while the store is shut", 0, leftoversDeleted)
    }

    /** No file and no key could be made: nothing answers — there is no older store to fall back to. */
    @Test
    fun noKeyAndNoFileAnswersNothing() {
        wrap.failWrap = true
        assertTrue(launch().settled() is LocalStores.State.Failed)
    }

    private companion object {
        const val ME = "5f0c1a2b-0000-4000-8000-000000000001"
        const val ANN = "0b6e9f6a-3c1d-4f7e-9a2b-5d8c7e6f1a2b"
        const val CHAT = "direct:ann"
    }
}
