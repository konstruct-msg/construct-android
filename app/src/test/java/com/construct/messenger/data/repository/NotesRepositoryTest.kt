package com.construct.messenger.data.repository

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.construct.messenger.data.local.CoreNoteStore
import com.construct.messenger.data.local.CoreSessionBlobStore
import com.construct.messenger.data.local.FakeSessionBlobStore
import com.construct.messenger.data.local.LocalMessagePayload
import com.construct.messenger.data.local.LocalStoreFeed
import com.construct.messenger.data.local.MessageRecord
import com.construct.messenger.data.local.Note
import com.construct.messenger.data.local.NoteStore
import com.construct.messenger.data.local.SessionBlobStore
import com.construct.messenger.data.model.DeliveryStatus
import com.construct.messenger.util.MediaWire
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import shared.proto.messaging.v1.Content.MediaAlbumMessage
import shared.proto.messaging.v1.Content.MediaMessage
import uniffi.construct_core.LocalStore

/** Settings → Notes, and the list they were until 2026-11-23. **Canon:** iOS `NotesLiveInTheEncryptedStoreTests`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NotesRepositoryTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val core = LocalStore.inMemory(ByteArray(32) { 1 })
    private val store = CoreNoteStore(LocalStoreFeed(core))
    private val prefs: SharedPreferences get() = context.getSharedPreferences(LegacyNotes.PREFS, Context.MODE_PRIVATE)
    private val media = File(context.filesDir, StorageRepository.MEDIA_DIR)

    @Before
    fun setUp() {
        prefs.edit().clear().commit()
        media.deleteRecursively()
        media.mkdirs()
    }

    @After
    fun close() = core.close()

    private fun list(vararg texts: String): String {
        val array = JSONArray()
        texts.forEachIndexed { i, t -> array.put(JSONObject().put("id", "id$i").put("text", t).put("createdAt", 1_700_000_000_000L - i)) }
        return array.toString()
    }

    private suspend fun TestScope.repository(kv: SessionBlobStore = FakeSessionBlobStore(), notes: NoteStore = store) =
        NotesRepository(notes, StorageRepository(context, notes), prefs, kv, TestScope(UnconfinedTestDispatcher(testScheduler)))
            .also { it.ready.join() }

    private suspend fun texts() = store.observe(50).first().map { it.text }

    /** Mutation: store the untrimmed text; store a blank one; edit a note that holds media. */
    @Test
    fun `words are trimmed, blank ones are not kept, and only words are edited`() = runTest {
        val notes = repository()
        assertFalse(notes.add("  \n"))
        assertTrue(notes.add(" first "))
        val note = store.observe(50).first().single()
        assertArrayEquals(Note.textBody("first"), note.body)
        assertTrue(notes.edit(note, "changed"))
        assertFalse(notes.edit(note, " "))
        assertEquals(listOf("changed"), texts())

        val photo = photo("p", "aaaa")
        store.insert(photo)
        assertFalse(notes.edit(photo, "a caption"))
        assertEquals("", store.get("p")!!.text)
    }

    private fun photo(id: String, mediaId: String) = Note(
        id = id, createdAtMs = 1,
        body = LocalMessagePayload.encode(
            MessageRecord(
                id = id, chatId = "", text = "", isSentByMe = true, timestampMs = 1, deliveryStatus = DeliveryStatus.SENT,
                mediaType = MediaWire.KIND_ALBUM,
                mediaPayload = MediaAlbumMessage.newBuilder().addItems(
                    MediaMessage.newBuilder().setMediaId(mediaId).setMimeType("image/jpeg")
                        .setEncryptionKey(com.google.protobuf.ByteString.copyFrom(ByteArray(32) { 7 })),
                ).build().toByteArray(),
            ),
        ),
    )

    /** Mutation: delete the note and leave its file; delete the file another note holds. */
    @Test
    fun `a note's file goes with the last note that holds it`() = runTest {
        val notes = repository()
        listOf("p1", "p2").forEach { store.insert(photo(it, "aaaa")) }
        File(media, "aaaa").writeText("x")
        assertTrue(notes.delete(store.get("p1")!!))
        assertEquals(listOf("aaaa"), media.list()!!.toList())
        assertTrue(notes.delete(store.get("p2")!!))
        assertEquals(emptyList<String>(), media.list()!!.toList())
    }

    /** What devices have: a plain list from before 2026-11-07, or the store's. Ids and dates stay. */
    @Test
    fun `the old list becomes notes wherever it is, and goes`() = runTest {
        prefs.edit().putString(LegacyNotes.KEY, list("newest", "oldest")).commit()
        val kv = FakeSessionBlobStore()
        repository(kv)
        assertEquals(listOf("newest", "oldest"), texts())
        assertEquals(1_700_000_000_000L, store.get("id0")!!.createdAtMs)
        assertNull(prefs.getString(LegacyNotes.KEY, null))
        assertNull(kv.get(LegacyNotes.KEY))
    }

    /** A crash between the two writes leaves both: the store's list wins, the plain copy still goes. */
    @Test
    fun `a list already in the store is not overwritten`() = runTest {
        val kv = FakeSessionBlobStore().also { it.rows[LegacyNotes.KEY] = list("written after").toByteArray() }
        prefs.edit().putString(LegacyNotes.KEY, list("left behind")).commit()
        repository(kv)
        assertEquals(listOf("written after"), texts())
        assertNull(prefs.getString(LegacyNotes.KEY, null))
    }

    /** Cut off part way, the list is still there; the next launch adds each once. Mutation: remove it first. */
    @Test
    fun `a move that fails keeps the list, and runs again without doubles`() = runTest {
        val kv = FakeSessionBlobStore().also { it.rows[LegacyNotes.KEY] = list("one", "two").toByteArray() }
        val failing = object : NoteStore by store {
            override suspend fun insert(note: Note) = if (note.id == "id1") error("full") else store.insert(note)
        }
        repository(kv, failing)
        assertEquals(listOf("one"), texts())
        assertTrue(kv.rows.containsKey(LegacyNotes.KEY))
        repository(kv)
        assertEquals(listOf("one", "two"), texts())
    }

    @Test
    fun `a refused move keeps the plain copy`() = runTest {
        val plain = list("only copy")
        prefs.edit().putString(LegacyNotes.KEY, plain).commit()
        repository(object : SessionBlobStore by FakeSessionBlobStore() {
            override suspend fun put(key: String, bytes: ByteArray) = error("refused")
        })
        assertEquals(plain, prefs.getString(LegacyNotes.KEY, null))
    }

    /** Not ours to delete, and they block no new note. */
    @Test
    fun `bytes that do not parse are left, and a new note goes in`() = runTest {
        val kv = FakeSessionBlobStore().also { it.rows[LegacyNotes.KEY] = "not json".toByteArray() }
        val notes = repository(kv)
        assertTrue(kv.rows.containsKey(LegacyNotes.KEY))
        assertTrue(notes.add("new"))
        assertEquals(listOf("new"), texts())
    }

    /** A refused write says so; what was typed is still in the editor. */
    @Test
    fun `a refused write is an answer, not a crash`() = runTest {
        val notes = repository(notes = object : NoteStore by store {
            override suspend fun insert(note: Note): Boolean = error("full")
        })
        assertFalse(notes.add("new"))
    }

    /** Remove [LegacyNotes] and its tests after this day. */
    @Test
    fun `the move is not past its last day`() {
        assertFalse(LocalDate.now().isAfter(LocalDate.parse(LegacyNotes.LAST_DAY)))
    }
}

/** The promise itself, on the real core store, on disk. */
class NotesOnDiskTest {
    @Test
    fun `a note is not readable in the store file`() = runTest {
        val dir = Files.createTempDirectory("notes").toFile()
        try {
            val core = LocalStore(File(dir, "construct.db").path, ByteArray(32) { 5 })
            CoreNoteStore(LocalStoreFeed(core)).insert(Note.ofText("Unmistakable-Note").copy(sourceName = "Unmistakable-Name"))
            core.close()
            val bytes = dir.listFiles()!!.map { String(it.readBytes(), Charsets.ISO_8859_1) }
            assertTrue(bytes.isNotEmpty())
            assertFalse(bytes.any { "Unmistakable" in it })
        } finally {
            dir.deleteRecursively()
        }
    }
}
