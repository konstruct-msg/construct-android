package com.construct.messenger.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.construct.messenger.util.ReactionQuickSet
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * The reaction row this device shows, learned from the reactions it sends ([ReactionQuickSet]).
 *
 * **Canon:** iOS `ReactionQuickSetStore` — one JSON value, `{"slots": […], "scores": {…}}`; a stored
 * row that is not [ReactionQuickSet.isWellFormed] falls back to the popular set. A habit, not a
 * message: nothing here is sent, and it goes with the app's data when the account is deleted.
 */
@Singleton
class ReactionQuickSetRepository internal constructor(private val prefs: SharedPreferences) {

    @Inject
    constructor(@ApplicationContext context: Context) :
        this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    private var set = load()
    private val row = MutableStateFlow(set.slots)

    /** The row, in display order. */
    val slots: StateFlow<List<String>> = row.asStateFlow()

    fun record(emoji: String) {
        set = set.recording(emoji)
        row.value = set.slots
        prefs.edit().putString(KEY, encode(set)).apply()
    }

    private fun load(): ReactionQuickSet =
        prefs.getString(KEY, null)
            ?.let { runCatching { decode(it) }.getOrNull() }
            ?.takeIf { it.isWellFormed }
            ?: ReactionQuickSet.INITIAL

    internal companion object {
        const val PREFS = "reactions_prefs"
        const val KEY = "construct.reactions.quickSet.v1"

        fun encode(set: ReactionQuickSet): String =
            JSONObject()
                .put("slots", JSONArray(set.slots))
                .put("scores", JSONObject().apply { set.scores.forEach { (k, v) -> put(k, v) } })
                .toString()

        fun decode(raw: String): ReactionQuickSet {
            val o = JSONObject(raw)
            val slots = o.getJSONArray("slots").let { a -> (0 until a.length()).map(a::getString) }
            val scores = o.getJSONObject("scores").let { s -> s.keys().asSequence().associateWith(s::getDouble) }
            return ReactionQuickSet(slots, scores)
        }
    }
}
