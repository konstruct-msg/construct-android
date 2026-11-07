package com.construct.messenger.data.repository

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.construct.messenger.data.local.CoreSessionBlobStore
import com.construct.messenger.data.local.FakeSessionBlobStore
import com.construct.messenger.data.local.SessionBlobStore
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uniffi.construct_core.LocalStore

/**
 * Settings → Drafts were JSON in `SharedPreferences`, a plain file (todo/278). **Canon:** iOS
 * `NotesLiveInTheEncryptedStoreTests`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DraftsRepositoryTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val prefs: SharedPreferences
        get() = context.getSharedPreferences(DraftsRepository.PREFS, Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        prefs.edit().clear().commit()
    }

    private fun plainList(vararg texts: String): String {
        val array = JSONArray()
        texts.forEachIndexed { i, t -> array.put(JSONObject().put("id", "id$i").put("text", t).put("createdAt", 1_700_000_000_000L + i)) }
        return array.toString()
    }

    private fun TestScope.repository(store: SessionBlobStore) =
        DraftsRepository(prefs, store, TestScope(UnconfinedTestDispatcher(testScheduler)))

    @Test
    fun `notes come back newest first, in the store and not in the preferences`() = runTest {
        val store = FakeSessionBlobStore()
        val drafts = repository(store)
        drafts.ready.join()

        assertTrue(drafts.add("first"))
        assertTrue(drafts.add("second"))

        assertEquals(listOf("second", "first"), drafts.drafts.value.map { it.text })
        assertTrue(String(store.rows.getValue(DraftsRepository.KEY)).contains("second"))
        assertNull(prefs.getString(DraftsRepository.KEY, null))
        val again = repository(store).also { it.ready.join() }
        assertEquals(listOf("second", "first"), again.drafts.value.map { it.text })
    }

    @Test
    fun `a delete removes that note only`() = runTest {
        val drafts = repository(FakeSessionBlobStore()).also { it.ready.join() }
        drafts.add("keep")
        drafts.add("drop")

        assertTrue(drafts.delete(drafts.drafts.value.first { it.text == "drop" }.id))

        assertEquals(listOf("keep"), drafts.drafts.value.map { it.text })
    }

    /** What is on every device today: text, order and dates kept, the plain copy gone. */
    @Test
    fun `a plain list moves into the store whole`() = runTest {
        val plain = plainList("newest", "oldest")
        prefs.edit().putString(DraftsRepository.KEY, plain).commit()
        val store = FakeSessionBlobStore()

        val drafts = repository(store).also { it.ready.join() }

        assertEquals(plain, String(store.rows.getValue(DraftsRepository.KEY)))
        assertEquals(listOf("newest", "oldest"), drafts.drafts.value.map { it.text })
        assertNull(prefs.getString(DraftsRepository.KEY, null))
    }

    /** A crash between the two writes leaves both: the store's list wins, the plain copy still goes. */
    @Test
    fun `a list already in the store is not overwritten`() = runTest {
        val store = FakeSessionBlobStore()
        store.rows[DraftsRepository.KEY] = plainList("written after the move").toByteArray()
        prefs.edit().putString(DraftsRepository.KEY, plainList("left behind")).commit()

        val drafts = repository(store).also { it.ready.join() }

        assertEquals(listOf("written after the move"), drafts.drafts.value.map { it.text })
        assertNull(prefs.getString(DraftsRepository.KEY, null))
    }

    /** The store would not take it: the only copy stays for the next launch. */
    @Test
    fun `a refused move keeps the plain copy`() = runTest {
        val plain = plainList("only copy")
        prefs.edit().putString(DraftsRepository.KEY, plain).commit()

        repository(Refusing(refusesPut = true)).ready.join()

        assertEquals(plain, prefs.getString(DraftsRepository.KEY, null))
    }

    /** Nothing is written over a list that could not be read: the cost is every note. */
    @Test
    fun `a failed read writes nothing`() = runTest {
        val store = Refusing(refusesGet = true)
        val drafts = repository(store).also { it.ready.join() }

        assertFalse(drafts.add("new"))
        assertFalse(drafts.delete("id0"))

        assertEquals(0, store.puts)
    }

    /** Nothing readable is lost by writing over them, and refusing to would block every new note. */
    @Test
    fun `bytes that do not parse read as no list, and a new note goes in`() = runTest {
        val store = FakeSessionBlobStore()
        store.rows[DraftsRepository.KEY] = "not json".toByteArray()

        val drafts = repository(store).also { it.ready.join() }
        assertEquals(emptyList<String>(), drafts.drafts.value.map { it.text })

        assertTrue(drafts.add("new"))
        assertEquals(listOf("new"), drafts.drafts.value.map { it.text })
    }

    private class Refusing(private val refusesGet: Boolean = false, private val refusesPut: Boolean = false) : SessionBlobStore {
        var puts = 0
        override suspend fun put(key: String, bytes: ByteArray) {
            puts += 1
            if (refusesPut) error("refused")
        }
        override suspend fun get(key: String): ByteArray? = if (refusesGet) error("refused") else null
        override suspend fun remove(key: String) = Unit
        override suspend fun withPrefix(prefix: String): Map<String, ByteArray> = emptyMap()
    }
}

/** The promise itself, on the real core store, on disk. */
class DraftsOnDiskTest {
    @Test
    fun `a note is not readable in the store file`() = runTest {
        val dir = Files.createTempDirectory("drafts").toFile()
        try {
            val path = File(dir, "construct.db").path
            val core = LocalStore(path, ByteArray(32) { 5 })
            val prefs = mock<SharedPreferences>()
            val drafts = DraftsRepository(prefs, CoreSessionBlobStore(core), TestScope(UnconfinedTestDispatcher(testScheduler)))
            drafts.ready.join()

            assertTrue(drafts.add("Unmistakable-Note"))
            core.close()

            val bytes = dir.listFiles()!!.filter { it.name.startsWith("construct.db") }.flatMap { it.readBytes().toList() }.toByteArray()
            assertFalse(String(bytes, Charsets.ISO_8859_1).contains("Unmistakable"))
            assertTrue(bytes.isNotEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }
}
