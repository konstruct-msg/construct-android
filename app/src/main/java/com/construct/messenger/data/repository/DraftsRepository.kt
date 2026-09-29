package com.construct.messenger.data.repository

import android.content.Context
import com.construct.messenger.data.model.Draft
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

/**
 * Settings → Drafts: notes kept on this device only, newest first.
 *
 * **Canon:** iOS `DraftsView` — a JSON list under `local_drafts`. Nothing here is sent anywhere,
 * and backup / device transfer already exclude app data (commit 428a12f).
 */
@Singleton
class DraftsRepository @Inject constructor(
    @param:ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val state = MutableStateFlow(load())
    val drafts: StateFlow<List<Draft>> = state.asStateFlow()

    fun add(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        state.update { listOf(Draft(UUID.randomUUID().toString(), trimmed, System.currentTimeMillis())) + it }
        save()
    }

    fun delete(id: String) {
        state.update { drafts -> drafts.filterNot { it.id == id } }
        save()
    }

    private fun load(): List<Draft> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                Draft(o.getString("id"), o.getString("text"), o.getLong("createdAt"))
            }
        }.getOrDefault(emptyList())
    }

    private fun save() {
        val array = JSONArray()
        state.value.forEach {
            array.put(JSONObject().put("id", it.id).put("text", it.text).put("createdAt", it.createdAt))
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private companion object {
        const val PREFS = "drafts_prefs"
        const val KEY = "local_drafts"
    }
}
