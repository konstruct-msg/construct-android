package com.construct.messenger.stealth

import android.content.Context
import android.content.SharedPreferences
import com.construct.messenger.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central policy for Stealth / Sealed Sender. **Canon:** iOS `StealthPolicy` (always on in
 * release, a kill-switch in debug builds).
 *
 * Decides whether to build a SealedInner at all ([shouldUseSealedSender]) and whether a Privacy
 * Pass token goes with it ([shouldConsumeToken]). **Per message:** a token goes with every sealed
 * envelope that carries no accepted credential. Under `MSG_STEALTH_TOKEN_POLICY=enforce` the
 * server refuses a sealed envelope without one, and it cannot tell message kinds apart inside the
 * seal. iOS dropped the per-stream scope (one token per recipient per 24 h) on 2026-07-15;
 * Android kept it until 2026-10-29, and most of its envelopes went without a token
 * (`decisions/intake-key-is-the-accounts-not-the-devices.md`, point 5).
 *
 * Exclusions are the caller's, as on iOS: `decisions/stealth-heartbeat-exclusion.md`.
 */
@Singleton
class StealthPolicy @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)

    init {
        // The removed per-stream state: a stored scope choice and the per-recipient times.
        // Idempotent, so a straggler heals on any start.
        val legacy = prefs.all.keys.filter { it == KEY_LEGACY_PER_MESSAGE || it.startsWith(LEGACY_STREAM_KEY_PREFIX) }
        if (legacy.isNotEmpty()) legacy.fold(prefs.edit()) { edit, key -> edit.remove(key) }.apply()
    }

    /** Sealed sender is always on in release; debug builds get a kill-switch. */
    val isEnabled: Boolean
        get() = if (BuildConfig.DEBUG) prefs.getBoolean(KEY_ENABLED, true) else true

    /** Debug-only toggle (no-op in release — [isEnabled] ignores the pref there). */
    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun shouldUseSealedSender(): Boolean = isEnabled

    /** A token goes with every sealed envelope without a credential. */
    fun shouldConsumeToken(): Boolean = isEnabled

    private companion object {
        const val PREFS_FILE_NAME = "stealth_policy_prefs"
        const val KEY_ENABLED = "stealth_enabled"
        const val KEY_LEGACY_PER_MESSAGE = "stealth_per_message"
        const val LEGACY_STREAM_KEY_PREFIX = "stream_last_token_"
    }
}
