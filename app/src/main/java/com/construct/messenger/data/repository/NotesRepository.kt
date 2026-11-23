package com.construct.messenger.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.construct.messenger.data.local.Note
import com.construct.messenger.data.local.NoteStore
import com.construct.messenger.data.local.SessionBlobStore
import com.construct.messenger.diagnostics.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.json.JSONArray

/**
 * Settings → Notes (vault todo 279). **Canon:** iOS `NotesModel`, `LegacyNotes`.
 *
 * A wipe here ends the process and clears every preference, so a list found there belongs to this
 * device's account.
 */
@Singleton
class NotesRepository internal constructor(
    private val notes: NoteStore,
    private val storage: StorageRepository,
    private val prefs: SharedPreferences,
    private val kv: SessionBlobStore,
    scope: CoroutineScope,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        notes: NoteStore,
        storage: StorageRepository,
        kv: SessionBlobStore,
    ) : this(
        notes, storage, context.getSharedPreferences(LegacyNotes.PREFS, Context.MODE_PRIVATE), kv,
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    /** The old list's move, at launch. The stores' gate holds it until the store is open. */
    internal val ready: Job = scope.launch {
        try {
            LegacyNotes.moveOutOfPrefs(prefs, kv)
            LegacyNotes.moveIntoNotes(kv, notes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "the old list not moved, next launch tries again", e)
        }
    }

    fun observe(limit: Int): Flow<List<Note>> = notes.observe(limit)
    fun observe(id: String): Flow<Note?> = notes.observe(id)
    fun search(query: String): Flow<List<Note>> = notes.search(query.trim(), SEARCH_LIMIT)

    /** True once [text] is stored; what was typed stays in the editor otherwise. */
    suspend fun add(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        return written("add") { notes.insert(Note.ofText(trimmed)) }
    }

    suspend fun edit(note: Note, text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || !note.isEditable) return false
        if (trimmed == note.text) return true
        return written("edit") {
            val released = notes.edit(note.id, Note.textBody(trimmed), System.currentTimeMillis()) ?: return@written false
            storage.deleteFiles(released)
            true
        }
    }

    suspend fun setPinned(note: Note, pinned: Boolean): Boolean = written("pin") { notes.setPinned(note.id, pinned) }

    /** The copies only this note held go with it. */
    suspend fun delete(note: Note): Boolean = written("delete") {
        storage.deleteFiles(notes.delete(listOf(note.id)))
        true
    }

    private suspend fun written(what: String, write: suspend () -> Boolean): Boolean = try {
        write()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "$what failed", e)
        false
    }

    private companion object {
        const val TAG = "Notes"
        const val SEARCH_LIMIT = 200
    }
}

/**
 * Notes were one JSON list of texts: in `SharedPreferences` until 2026-11-07 (todo 278), in the
 * store's `kv` after. Read once, into the notes table. Remove after [LAST_DAY]; `NotesRepositoryTest`
 * fails then.
 */
internal object LegacyNotes {
    private const val TAG = "Notes"
    const val LAST_DAY = "2027-03-01"
    const val PREFS = "drafts_prefs"

    /** The key the list has had in both places. */
    const val KEY = "local_drafts"

    /** The plain copy goes only once the store holds a list; a list already there is the newer one. */
    suspend fun moveOutOfPrefs(prefs: SharedPreferences, kv: SessionBlobStore) {
        val plain = prefs.getString(KEY, null) ?: return
        if (kv.get(KEY) == null) kv.put(KEY, plain.toByteArray())
        prefs.edit().remove(KEY).commit()
    }

    /**
     * Each becomes a note under the id it had, and the list goes once all of them are in. Run again
     * after a failure it adds nothing twice. Bytes that do not parse are left: they hold no list
     * anybody could read, and deleting them is not this function's to decide.
     */
    suspend fun moveIntoNotes(kv: SessionBlobStore, notes: NoteStore) {
        val bytes = kv.get(KEY) ?: return
        val old = try {
            val array = JSONArray(String(bytes))
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                Note.ofText(o.getString("text"), id = o.getString("id"), createdAtMs = o.getLong("createdAt"))
            }
        } catch (e: org.json.JSONException) {
            Log.w(TAG, "the stored list does not parse", e)
            return
        }
        old.forEach { notes.insert(it) }
        kv.remove(KEY)
        Log.i(TAG, "${old.size} note(s) moved into the store")
    }
}
