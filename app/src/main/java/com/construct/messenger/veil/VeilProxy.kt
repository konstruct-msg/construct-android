package com.construct.messenger.veil

import android.content.Context
import com.construct.messenger.data.auth.AuthSessionManager
import com.construct.messenger.diagnostics.Log
import com.construct.messenger.transport.TransportEvents
import com.construct.messenger.transport.TransportRoute
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
    private val provisioner: VeilFrontProvisioner,
    private val modes: VeilModeStore,
    private val events: TransportEvents,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val modeState: StateFlow<VeilMode> get() = modes.state
    private val info = MutableStateFlow(VeilStartInfo())
    val startInfo: StateFlow<VeilStartInfo> = info.asStateFlow()

    /** Native-TLS veil-front dialer (Conscrypt). Used only when [nativeTlsEnabled]; off by default. */
    private val externalDialer = VeilFrontExternalDialer(onListenerLost = { port, relay ->
        // The same verdict a refused loopback connection gets: the proxy is gone, replace it.
        events.post(
            TransportRoute.Event.RpcFailed(
                TransportRoute.RpcFailure.STALE_LOCAL_PROXY, TransportRoute.Target.Veil(port, relay), foreground = true,
            ),
        )
    })

    /** Which tunnel is up: the Conscrypt listener or the rustls coordinator. They answer liveness differently. */
    @Volatile private var nativeUp = false

    /**
     * In-session consecutive-failure count per front address, so [start] dials a blocked front last
     * instead of always re-dialling the newest. In-memory only and cleared on a network path change
     * ([onNetworkChanged]) — nothing about which front died on which network is persisted
     * (`decisions/no-client-side-network-learning.md`). A front is only deprioritised, never dropped;
     * its pin stays in [VeilFrontStore], so eviction is never censor-triggerable. **Canon:** iOS
     * `RelayPool`.
     */
    private val failures = java.util.concurrent.ConcurrentHashMap<String, Int>()

    fun saveMode(value: VeilMode) = modes.save(value)

    /**
     * Terminate veil-front TLS in Conscrypt (a genuine Android ClientHello) instead of
     * rustls-Chrome131. **Default on** — validated 2026-10-09 on a real device (Redmi, Android 11)
     * against the production relay: exporter parity with the relay's rustls, a mainstream-Conscrypt
     * ClientHello (not the Chrome131 oddball), end-to-end key-bound AUTH, and bucket-aligned L4. The
     * rustls path stays the automatic fallback when the dial / SPKI pin / bind fails. Canon: iOS
     * `veil_front_native_tls` (default on since #141).
     */
    fun nativeTlsEnabled(): Boolean = prefs.getBoolean(KEY_NATIVE_TLS, true)

    fun setNativeTls(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NATIVE_TLS, enabled).apply()
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
        // No front is bundled (`VeilFrontStore`). With none learned, ask the server over the path
        // that works now; failing that there is nothing to dial, said plainly rather than thrown.
        var candidates = VeilFrontPool.order(fronts.all(), failures)
        if (candidates.isEmpty()) {
            provisioner.ensureFront()
            candidates = VeilFrontPool.order(fronts.all(), failures)
        }
        if (candidates.isEmpty()) return failed(null, NO_FRONT)

        // The capability is issued over whatever path currently carries unary calls; an expired
        // token would be refused.
        authSession.ensureFresh()

        // Try fronts in order (fewest failures, newest) until one comes up, so a blocked front
        // rotates to the next instead of stranding the device on the direct path. Each failure is
        // recorded so the next attempt — here or on a later start — prefers a different front.
        var lastFailure: StartResult.Failed? = null
        for (front in candidates.take(MAX_DIAL_ATTEMPTS)) {
            when (val result = dialOne(front.relay)) {
                is StartResult.Up -> {
                    failures.remove(front.address)
                    return result
                }
                is StartResult.Failed -> {
                    failures.merge(front.address, 1) { n, _ -> n + 1 }
                    lastFailure = result
                    Log.w(TAG, "front ${front.address} failed: ${result.reason}; trying next")
                }
            }
        }
        // Every tried front failed. The router fires maintainFloor() fire-and-forget after this,
        // so a replacement is fetched without delaying the failure event into cooldown.
        return lastFailure?.also { info.value = VeilStartInfo(lastError = it.reason) } ?: failed(null, NO_FRONT)
    }

    /** One front: ensure a capability, then dial (native Conscrypt first when enabled, rustls else). */
    private suspend fun dialOne(relay: VeilRelay): StartResult {
        // Key-bound when there is one — `veil_start` then signs with `veil_sk` and ignores the
        // bearer field. The bearer one is still what opens the first tunnel on a new device.
        val keyBound = capabilities.currentKeyBound(relay)
        val bearer = if (keyBound != null) capabilities.current(relay).orEmpty() else {
            capabilities.ensure(relay) ?: return StartResult.Failed(relay.address, "no capability for ${relay.address}")
        }
        // Opt-in native-TLS path: Conscrypt terminates TLS to the relay (genuine Android hello),
        // Rust runs the post-TLS ferry. Falls back to rustls (below) if the dial/pin/bind fails.
        if (nativeTlsEnabled()) {
            val nativePort = withContext(Dispatchers.IO) {
                externalDialer.start(relay, bearer, keyBound?.capabilityB64.orEmpty(), keyBound?.veilSkHex.orEmpty())
            }
            if (nativePort != null) {
                nativeUp = true
                info.value = VeilStartInfo(relay.address, VeilMethod.VEIL_FRONT, latencyMs = null, lastError = null)
                Log.i(TAG, "VEIL up: ${relay.address} via VEIL_FRONT (native Conscrypt), local :$nativePort, key-bound ${keyBound != null}")
                return StartResult.Up(relay.address, nativePort)
            }
            Log.w(TAG, "native Conscrypt veil-front unavailable for ${relay.address}; falling back to rustls")
        }
        nativeUp = false
        val outcome = withContext(Dispatchers.IO) { startNative(relay, bearer, keyBound) }
        if (outcome.port <= 0) return StartResult.Failed(relay.address, outcome.error ?: "veil_start failed")
        info.value = VeilStartInfo(relay.address, outcome.method, outcome.latencyMs, lastError = null)
        Log.i(TAG, "VEIL up: ${relay.address} via ${outcome.method} in ${outcome.latencyMs}ms, local :${outcome.port}, key-bound ${keyBound != null}")
        return StartResult.Up(relay.address, outcome.port)
    }

    /** Fronts with a failure recorded this session — treated as currently unavailable for the floor. */
    private fun failingAddresses(): Set<String> = failures.keys.toSet()

    /**
     * Keep ≥[VeilFrontPool.FLOOR] usable fronts: if losing one has dropped the count, ask a working
     * front for its signed alternates (adds a replacement without touching the dead one). Fire-and-
     * forget from the router on a tunnel coming up, a start failure, and front preparation.
     */
    suspend fun maintainFloor() {
        if (modes.state.value == VeilMode.OFF) return
        provisioner.topUpFloorIfNeeded(excluding = failingAddresses())
    }

    /** Network path changed: old failures say nothing about the new path — start the counts over. */
    fun onNetworkChanged() = failures.clear()

    /**
     * Once the router has routed through the tunnel: get or renew the key-bound capability over
     * it. Throttled in [VeilCapabilities.renewKeyBound].
     */
    suspend fun renewKeyBound() {
        val relay = fronts.preferred() ?: return
        authSession.ensureFresh()
        capabilities.renewKeyBound(relay)
    }

    /**
     * Learn a front while the direct path still works, so VEIL has one when it is needed. No-op
     * with VEIL off or a front known; rate-limited in [VeilFrontProvisioner].
     */
    suspend fun prepareFront() {
        if (modes.state.value == VeilMode.OFF) return
        provisioner.ensureFront()
        provisioner.topUpFloorIfNeeded(excluding = failingAddresses())
    }

    fun stop() {
        nativeUp = false
        runCatching { externalDialer.stop() } // closes the native listener + live Conscrypt sessions
        runCatching { VeilLib.INSTANCE.veil_stop() }
        info.update { it.copy(relay = null, method = null, latencyMs = null) }
    }

    /**
     * Whether the tunnel gRPC points at still lives. `veil_is_alive` asks the rustls coordinator,
     * which the Conscrypt path never starts — asked alone it said "dead" for every stall over a
     * healthy native tunnel, and each one was replaced as a hard failure.
     */
    fun isAlive(): Boolean = tunnelAlive(nativeUp, externalDialer.isListening) {
        runCatching { VeilLib.INSTANCE.veil_is_alive() != 0 }.getOrDefault(false)
    }

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

    companion object {
        /** [StartResult.Failed.reason] when no front has been learned; the Network screen names it. */
        const val NO_FRONT = "no VEIL front: import a veil-config link"
        private const val TAG = "VEIL"
        private const val PREFS = "veil_prefs"
        private const val KEY_NATIVE_TLS = "native_tls"
        private const val SCORES_FILE = "veil_scores.sqlite"
        private const val ERROR_BUFFER = 512

        /** Fronts to dial in one [start] before giving up — bounds latency when several are blocked. */
        private const val MAX_DIAL_ATTEMPTS = 3
    }
}

internal fun tunnelAlive(nativeUp: Boolean, nativeListening: Boolean, coordinatorAlive: () -> Boolean): Boolean =
    if (nativeUp) nativeListening else coordinatorAlive()
