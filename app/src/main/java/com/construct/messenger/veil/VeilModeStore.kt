package com.construct.messenger.veil

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Off: always direct. Auto: direct first, VEIL once the direct path actually fails. On: only VEIL —
 * with no tunnel up the app is offline, never direct. Canon: iOS `VeilMode`, and its platform
 * default (Auto).
 */
enum class VeilMode {
    OFF, AUTO, ON;

    /**
     * Whether anything may connect to our servers outside the tunnel. Not under On (owner,
     * 2026-10-13, vault `decisions/veil-on-never-goes-direct.md`): the person asked not to be seen,
     * so with no tunnel up the app is offline rather than direct. Until then On meant "prefer
     * VEIL" — every gap in the tunnel put the signed-in connection on the API domain while the
     * settings said all traffic went through VEIL. Canon: iOS `VeilMode.allowsDirectPath`.
     */
    val allowsDirectPath: Boolean get() = this != ON
}

/**
 * The chosen [VeilMode], apart from the proxy so that whatever opens a connection — the gRPC
 * channels, the well-known fetch — can ask it without depending on VEIL itself.
 */
@Singleton
class VeilModeStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mode = MutableStateFlow(load())
    val state: StateFlow<VeilMode> = mode.asStateFlow()

    fun save(value: VeilMode) {
        prefs.edit().putString(KEY_MODE, value.name).apply()
        mode.value = value
    }

    /** Never set means Auto — iOS's platform default. */
    private fun load(): VeilMode =
        prefs.getString(KEY_MODE, null)?.let { runCatching { VeilMode.valueOf(it) }.getOrNull() } ?: VeilMode.AUTO

    private companion object {
        /** VeilProxy's file: the mode was kept there before this class, and stays where it was. */
        const val PREFS = "veil_prefs"
        const val KEY_MODE = "mode"
    }
}
