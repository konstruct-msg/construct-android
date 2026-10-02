package com.construct.messenger.veil

import android.content.Context
import com.construct.messenger.data.auth.AuthSessionManager
import com.construct.messenger.diagnostics.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * Off: always direct. Auto: direct first, VEIL once the direct path actually fails. On: always
 * VEIL. Canon: iOS `VeilMode`, and its platform default (Auto).
 */
enum class VeilMode { OFF, AUTO, ON }

/** What the last start did, for the Network screen. Which path is in use is the router's state. */
data class VeilStartInfo(
    val relay: String? = null,
    val method: VeilMethod? = null,
    val latencyMs: Int? = null,
    /** Why the last start failed, as construct-veil named it; null once one succeeds. */
    val lastError: String? = null,
)

/**
 * The VEIL proxy as an effector: bring a tunnel up, tear it down, say whether it lives. It decides
 * nothing — when to start and stop is `TransportRouter`'s, from the mode and what the wire shows.
 * **Canon:** iOS `VeilProxyManager` (mode, relay state) + `NativeVeilRuntime` (the FFI).
 */
@Singleton
class VeilProxy @Inject constructor(
    @ApplicationContext private val context: Context,
    private val capabilities: VeilCapabilities,
    private val authSession: AuthSessionManager,
    private val fronts: VeilFrontStore,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mode = MutableStateFlow(loadMode())
    val modeState: StateFlow<VeilMode> = mode.asStateFlow()
    private val info = MutableStateFlow(VeilStartInfo())
    val startInfo: StateFlow<VeilStartInfo> = info.asStateFlow()

    fun saveMode(value: VeilMode) {
        prefs.edit().putString(KEY_MODE, value.name).apply()
        mode.value = value
    }

    sealed interface StartResult {
        data class Up(val relay: String, val port: Int) : StartResult
        data class Failed(val relay: String?, val reason: String) : StartResult
    }

    /**
     * Capability, then `veil_start` (which runs the probe race and blocks for it). Does not route
     * anything: the router points gRPC at the port when it adopts the result.
     */
    suspend fun start(): StartResult {
        // The capability is issued over whatever path currently carries unary calls; an expired
        // token would be refused.
        // No front is bundled (`VeilFrontStore`): with none learned there is nothing to dial, and
        // that is said plainly rather than thrown.
        val relay = fronts.preferred() ?: return failed(null, NO_FRONT)
        authSession.ensureFresh()
        // Key-bound when there is one — `veil_start` then signs with `veil_sk` and ignores the
        // bearer field. The bearer one is still what opens the first tunnel on a new device.
        val keyBound = capabilities.currentKeyBound(relay)
        val bearer = if (keyBound != null) capabilities.current(relay).orEmpty() else {
            capabilities.ensure(relay) ?: return failed(relay, "no capability for ${relay.address}")
        }
        val outcome = withContext(Dispatchers.IO) { startNative(relay, bearer, keyBound) }
        if (outcome.port <= 0) return failed(relay, outcome.error ?: "veil_start failed")
        info.value = VeilStartInfo(relay.address, outcome.method, outcome.latencyMs, lastError = null)
        Log.i(TAG, "VEIL up: ${relay.address} via ${outcome.method} in ${outcome.latencyMs}ms, local :${outcome.port}, key-bound ${keyBound != null}")
        return StartResult.Up(relay.address, outcome.port)
    }

    /**
     * Once the router has routed through the tunnel: get or renew the key-bound capability over
     * it. Throttled in [VeilCapabilities.renewKeyBound].
     */
    suspend fun renewKeyBound() {
        val relay = fronts.preferred() ?: return
        authSession.ensureFresh()
        capabilities.renewKeyBound(relay)
    }

    fun stop() {
        runCatching { VeilLib.INSTANCE.veil_stop() }
        info.update { it.copy(relay = null, method = null, latencyMs = null) }
    }

    fun isAlive(): Boolean = runCatching { VeilLib.INSTANCE.veil_is_alive() != 0 }.getOrDefault(false)

    private fun failed(relay: VeilRelay?, reason: String): StartResult {
        Log.e(TAG, "VEIL start via ${relay?.address ?: "no front"} failed: $reason")
        info.value = VeilStartInfo(lastError = reason)
        return StartResult.Failed(relay?.address, reason)
    }

    private class Outcome(val port: Int, val method: VeilMethod?, val latencyMs: Int, val error: String?)

    private fun startNative(relay: VeilRelay, bearerB64: String, keyBound: VeilCapabilities.KeyBound?): Outcome {
        val lib = VeilLib.INSTANCE
        val request = VeilStartRequest.ByValue().apply {
            relay_addr = relay.address
            bundle = "" // obfs4 bridge line: obfs4 is disabled below, as on iOS.
            tls_sni = relay.sni
            spki_hex = relay.spkiHex
            host_header = ""
            wt_base_path = ""
            allowed_methods = VeilMethod.DISABLED_EXCEPT_VEIL_FRONT
            // Method scores survive restarts; the file is this device's, not a backup's.
            scores_path = File(context.noBackupFilesDir, SCORES_FILE).absolutePath
            veil_front_ticket_b64 = bearerB64
            // Empty = AUTH v3 not configured: the relay is authenticated with the bearer ticket.
            veil_capability_v2_b64 = keyBound?.capabilityB64.orEmpty()
            veil_sk_hex = keyBound?.veilSkHex.orEmpty()
        }
        val out = VeilStartResult()
        val rc = runCatching { lib.veil_start(request, out) }.getOrElse {
            return Outcome(0, null, 0, "veil_start threw: ${it.message}")
        }
        val port = out.port.toInt() and 0xFFFF
        if (rc != 0 || port == 0) return Outcome(0, null, 0, lastError(lib) ?: "veil_start rc=$rc")
        return Outcome(port, VeilMethod.of(out.method.toInt()), out.latency_ms, null)
    }

    private fun lastError(lib: VeilLib): String? = runCatching {
        val buf = ByteArray(ERROR_BUFFER)
        val len = lib.veil_last_error(buf, SizeT(buf.size.toLong())).toLong().toInt()
        if (len <= 0) null else String(buf, 0, minOf(len, buf.size - 1), Charsets.UTF_8)
    }.getOrNull()

    /** Never set means Auto — iOS's platform default. */
    private fun loadMode(): VeilMode =
        prefs.getString(KEY_MODE, null)?.let { runCatching { VeilMode.valueOf(it) }.getOrNull() } ?: VeilMode.AUTO

    companion object {
        /** [StartResult.Failed.reason] when no front has been learned; the Network screen names it. */
        const val NO_FRONT = "no VEIL front: import a veil-config link"
        private const val TAG = "VEIL"
        private const val PREFS = "veil_prefs"
        private const val KEY_MODE = "mode"
        private const val SCORES_FILE = "veil_scores.sqlite"
        private const val ERROR_BUFFER = 512
    }
}
