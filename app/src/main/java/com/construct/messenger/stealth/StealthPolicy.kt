package com.construct.messenger.stealth

import android.content.Context
import android.content.SharedPreferences
import com.construct.messenger.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central policy for Stealth / Sealed Sender — mirrors iOS `StealthPolicy`
 * (stealth-sealed-sender-v2 Phase 4 semantics: always-on in release, a debug
 * toggle in debug builds).
 *
 * Decides:
 *  - whether to build a SealedInner at all ([shouldUseSealedSender]);
 *  - whether to spend a Privacy Pass token on this send ([shouldConsumeToken]):
 *    per-stream (default, at most one token per recipient per 24h) vs
 *    per-message (one on every send — higher wallet burn, unlinkable streams).
 *
 * Exclusions (E2E heartbeats, multi-device sync, pure session control) are the
 * caller's responsibility, same as on iOS — see
 * `construct-docs/decisions/stealth-heartbeat-exclusion.md`.
 */
@Singleton
class StealthPolicy @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)

    /** Sealed sender is always on in release; debug builds get a kill-switch. */
    val isEnabled: Boolean
        get() = if (BuildConfig.DEBUG) prefs.getBoolean(KEY_ENABLED, true) else true

    /** Debug-only toggle (no-op in release — [isEnabled] ignores the pref there). */
    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** true = spend a token on every send; false = per-stream (default). */
    var isPerMessage: Boolean
        get() = prefs.getBoolean(KEY_PER_MESSAGE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_PER_MESSAGE, value).apply()
        }

    fun shouldUseSealedSender(): Boolean = isEnabled

    /** Should a token be spent for [recipientId] under the current scope? */
    fun shouldConsumeToken(recipientId: String): Boolean {
        if (!isEnabled) return false
        if (isPerMessage) return true
        val last = prefs.getLong(streamKey(recipientId), 0L)
        return System.currentTimeMillis() - last >= PER_STREAM_WINDOW_MS
    }

    /** Record that a token was spent for [recipientId] (per-stream bookkeeping). */
    fun recordTokenConsumed(recipientId: String) {
        if (!isPerMessage) {
            prefs.edit().putLong(streamKey(recipientId), System.currentTimeMillis()).apply()
        }
    }

    /** Call on logout / identity reset. */
    fun clearStreamState() {
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(STREAM_KEY_PREFIX) }.forEach(editor::remove)
        editor.apply()
    }

    private fun streamKey(recipientId: String) = STREAM_KEY_PREFIX + recipientId

    private companion object {
        const val PREFS_FILE_NAME = "stealth_policy_prefs"
        const val KEY_ENABLED = "stealth_enabled"
        const val KEY_PER_MESSAGE = "stealth_per_message"
        const val STREAM_KEY_PREFIX = "stream_last_token_"
        const val PER_STREAM_WINDOW_MS = 24 * 60 * 60 * 1000L
    }
}
