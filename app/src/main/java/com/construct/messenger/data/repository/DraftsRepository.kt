package com.construct.messenger.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.construct.messenger.data.local.SessionBlobStore
import com.construct.messenger.data.model.Draft
import com.construct.messenger.diagnostics.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Settings → Drafts: notes kept on this device only, newest first
 * (`decisions/notes-stay-on-the-device.md`).
 *
 * In the core store's encrypted `kv`, so they go when the store goes. Until 2026-11-07 they were
 * JSON in `SharedPreferences`, a plain file (todo/278). The key and the bytes are the same, and a
 * list still in the preferences moves in when this is built, at launch ([com.construct.messenger.KonstructApp]).
 * A wipe here ends the process and clears every preference, so a list found there belongs to this
 * device's account. **Canon:** iOS `NotesStore`.
 */
@Singleton
class DraftsRepository internal constructor(
    private val prefs: SharedPreferences,
    private val store: SessionBlobStore,
    scope: CoroutineScope,
) {
    @Inject
    constructor(@ApplicationContext context: Context, store: SessionBlobStore) : this(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
        store,
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    private val lock = Mutex()
    private val state = MutableStateFlow<List<Draft>>(emptyList())
    val drafts: StateFlow<List<Draft>> = state.asStateFlow()

    /** The move and the first read. The store's gate holds it until the store is open. */
    internal val ready: Job = scope.launch {
        lock.withLock {
            runCatching { moveOutOfPrefs() }.onFailure { Log.e(TAG, "not moved out of the preferences, next launch tries again", it) }
            runCatching { state.value = read() }.onFailure { Log.e(TAG, "not read", it) }
        }
    }

    /** True once [text] is stored on top of the list. A list that could not be read is not written over. */
    suspend fun add(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        return lock.withLock {
            runCatching {
                val notes = listOf(Draft(UUID.randomUUID().toString(), trimmed, System.currentTimeMillis())) + read()
                store.put(KEY, encode(notes))
                state.value = notes
            }.onFailure { Log.e(TAG, "not saved", it) }.isSuccess
        }
    }

    suspend fun delete(id: String): Boolean = lock.withLock {
        runCatching {
            val notes = read().filterNot { it.id == id }
            store.put(KEY, encode(notes))
            state.value = notes
        }.onFailure { Log.e(TAG, "not deleted", it) }.isSuccess
    }

    /** The plain copy goes only once the store holds a list; a list already there is the newer one. */
    private suspend fun moveOutOfPrefs() {
        val plain = prefs.getString(KEY, null) ?: return
        if (store.get(KEY) == null) store.put(KEY, plain.toByteArray())
        prefs.edit().remove(KEY).commit()
    }

    /** Throws when the store cannot answer. Bytes that do not parse hold no list anybody could read. */
    private suspend fun read(): List<Draft> {
        val bytes = store.get(KEY) ?: return emptyList()
        return runCatching {
            val array = JSONArray(String(bytes))
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                Draft(o.getString("id"), o.getString("text"), o.getLong("createdAt"))
            }
        }.onFailure { Log.w(TAG, "the stored list does not parse", it) }.getOrDefault(emptyList())
    }

    private fun encode(notes: List<Draft>): ByteArray {
        val array = JSONArray()
        notes.forEach { array.put(JSONObject().put("id", it.id).put("text", it.text).put("createdAt", it.createdAt)) }
        return array.toString().toByteArray()
    }

    internal companion object {
        const val TAG = "Drafts"
        const val PREFS = "drafts_prefs"
        const val KEY = "local_drafts"
    }
}
