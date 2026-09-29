package com.construct.messenger.veil

import android.content.Context
import com.construct.messenger.data.api.GrpcClient
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Off: always the direct path. On: always through a front. (iOS also has Auto; not yet here.) */
enum class VeilMode { OFF, ON }

data class VeilState(
    val mode: VeilMode = VeilMode.OFF,
    val running: Boolean = false,
    val relay: String? = null,
    val method: VeilMethod? = null,
    val latencyMs: Int? = null,
    /** Why the last start failed, as construct-veil named it; null once one succeeds. */
    val lastError: String? = null,
    val starting: Boolean = false,
)

/**
 * The censorship-resistant path: a local port construct-veil listens on, tunnelled to a front
 * that forwards to the server, and both gRPC channels pointed at it. **Canon:** iOS
 * `VeilProxyManager` + `NativeVeilRuntime` — the manual half. Auto mode (switch when the direct
 * path is being cut) is a routing decision and stays out of Kotlin until it lives in the core
 * (`docs/IMPLEMENTATION_PLAN.md` §5).
 *
 * Why it exists: on a censored network the stream opens, delivers the first frames, then hears
 * nothing — seen on a device 2026-09-29, fixed by a VPN, i.e. by a different path.
 */
@Singleton
class VeilProxy @Inject constructor(
    @ApplicationContext private val context: Context,
    private val grpcClient: GrpcClient,
    private val capabilities: VeilCapabilities,
    private val authSession: AuthSessionManager,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val state = MutableStateFlow(VeilState(mode = loadMode()))
    val uiState: StateFlow<VeilState> = state.asStateFlow()
    private val mutex = Mutex()

    suspend fun setMode(mode: VeilMode) {
        prefs.edit().putString(KEY_MODE, mode.name).apply()
        state.update { it.copy(mode = mode) }
        apply()
    }

    /**
     * Bring the path in line with the mode. Idempotent: a live tunnel is kept. Every failure
     * leaves the direct path in place and says why in [VeilState.lastError].
     */
    suspend fun apply() = mutex.withLock {
        if (state.value.mode == VeilMode.OFF) {
            stopLocked()
            return@withLock
        }
        if (grpcClient.veilPort != null && isAlive()) return@withLock

        state.update { it.copy(starting = true) }
        try {
            // The capability is issued over the direct path while it still carries unary calls;
            // an expired token would be refused.
            authSession.ensureFresh()
            val relay = VeilSeeds.relays.first()
            val capability = capabilities.ensure(relay)
            if (capability == null) {
                fail(relay, "no capability for ${relay.address}")
                return@withLock
            }
            val outcome = withContext(Dispatchers.IO) { start(relay, capability) }
            if (outcome.port <= 0) {
                fail(relay, outcome.error ?: "veil_start failed")
                return@withLock
            }
            grpcClient.routeThrough(outcome.port)
            state.update {
                it.copy(running = true, relay = relay.address, method = outcome.method, latencyMs = outcome.latencyMs, lastError = null)
            }
            Log.i(TAG, "VEIL up: ${relay.address} via ${outcome.method} in ${outcome.latencyMs}ms, local :${outcome.port}")
        } finally {
            state.update { it.copy(starting = false) }
        }
    }

    /** The tunnel died under us (the stream went silent): start it again if the mode says so. */
    suspend fun restartIfDead() {
        if (state.value.mode == VeilMode.ON && grpcClient.veilPort != null && !isAlive()) {
            Log.w(TAG, "VEIL tunnel is gone — restarting")
            mutex.withLock { stopLocked() }
            apply()
        }
    }

    private fun stopLocked() {
        if (grpcClient.veilPort != null) grpcClient.routeThrough(null)
        runCatching { VeilLib.INSTANCE.veil_stop() }
        state.update { it.copy(running = false, relay = null, method = null, latencyMs = null) }
    }

    private fun fail(relay: VeilRelay, reason: String) {
        Log.e(TAG, "VEIL start via ${relay.address} failed: $reason — staying on the direct path")
        grpcClient.routeThrough(null)
        state.update { it.copy(running = false, relay = null, method = null, latencyMs = null, lastError = reason) }
    }

    private fun isAlive(): Boolean = runCatching { VeilLib.INSTANCE.veil_is_alive() != 0 }.getOrDefault(false)

    private class Outcome(val port: Int, val method: VeilMethod?, val latencyMs: Int, val error: String?)

    /** Blocking: construct-veil runs the probe race inside the call. */
    private fun start(relay: VeilRelay, capabilityB64: String): Outcome {
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
            veil_front_ticket_b64 = capabilityB64
            // B1 (key-bound) is not provisioned on Android yet: empty falls back to the ticket.
            veil_capability_v2_b64 = ""
            veil_sk_hex = ""
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

    private fun loadMode(): VeilMode =
        prefs.getString(KEY_MODE, null)?.let { runCatching { VeilMode.valueOf(it) }.getOrNull() } ?: VeilMode.OFF

    private companion object {
        const val TAG = "VEIL"
        const val PREFS = "veil_prefs"
        const val KEY_MODE = "mode"
        const val SCORES_FILE = "veil_scores.sqlite"
        const val ERROR_BUFFER = 512
    }
}
