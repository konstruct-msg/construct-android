package com.construct.messenger.transport

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.construct.messenger.data.api.GrpcClient
import com.construct.messenger.diagnostics.Log
import com.construct.messenger.transport.TransportRoute.Effect
import com.construct.messenger.transport.TransportRoute.Event
import com.construct.messenger.transport.TransportRoute.State
import com.construct.messenger.veil.VeilMode
import com.construct.messenger.veil.VeilProxy
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns the route: feeds [TransportRoute] what the wire shows and applies what it answers — the
 * proxy up or down, gRPC pointed at a port or the server, a fresh client, the end of a cooldown.
 * **Canon:** iOS `TransportRouter` (actor): serial `send`, the proxy start off the lock so the
 * machine keeps answering during a probe, and a generation so a superseded start is torn down.
 */
@Singleton
class TransportRouter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val veil: VeilProxy,
    private val grpcClient: GrpcClient,
    private val events: TransportEvents,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val state = MutableStateFlow(TransportRoute.initial(veil.modeState.value.toRouteMode(), censored = false, reachable = true))
    val route: StateFlow<State> = state.asStateFlow()

    private var started = false
    private var generation = 0L
    private var startJob: Job? = null
    private var cooldownJob: Job? = null

    /** Once, when messaging starts: listen, and fire the start the initial state asks for. */
    fun start() {
        synchronized(this) {
            if (started) return
            started = true
        }
        scope.launch { events.events.collect { send(it) } }
        watchNetwork()
        Log.i(TAG, "route starts ${state.value} (mode ${veil.modeState.value})")
        if (state.value == State.VeilProbing) scope.launch { mutex.withLock { beginProxyStart() } }
    }

    suspend fun setMode(mode: VeilMode) {
        veil.saveMode(mode)
        send(Event.VeilModeChanged(censored = false))
    }

    suspend fun send(event: Event) = mutex.withLock {
        val from = state.value
        val outcome = TransportRoute.reduce(from, event, veil.modeState.value.toRouteMode(), nowMs = System.currentTimeMillis())
        state.value = outcome.state
        if (from != outcome.state || outcome.effects.isNotEmpty()) {
            Log.i(TAG, "$from → ${outcome.state} | $event | ${outcome.effects}")
        }
        for (effect in outcome.effects) apply(effect)
    }

    private fun apply(effect: Effect) {
        when (effect) {
            Effect.InvalidateGrpcClient -> grpcClient.reconnect()
            is Effect.SetVeilPort -> grpcClient.routeThrough(effect.port)
            Effect.RequestProxyStop -> {
                cooldownJob?.cancel()
                // An in-flight start is superseded: its result, if it comes, is torn down.
                generation++
                startJob?.cancel()
                startJob = null
                veil.stop()
            }
            Effect.RequestProxyStart -> beginProxyStart()
            is Effect.ScheduleCooldownEnd -> {
                cooldownJob?.cancel()
                cooldownJob = scope.launch {
                    delay((effect.atMs - System.currentTimeMillis()).coerceAtLeast(0))
                    send(Event.CooldownElapsed)
                }
            }
        }
    }

    /** Off the lock: `veil_start` blocks for the whole probe, and the machine must keep answering. */
    private fun beginProxyStart() {
        val mine = ++generation
        startJob?.cancel()
        startJob = scope.launch {
            val result = veil.start()
            val current = mutex.withLock { mine == generation }
            if (!current) {
                if (result is VeilProxy.StartResult.Up) veil.stop()
                return@launch
            }
            send(
                when (result) {
                    is VeilProxy.StartResult.Up -> Event.ProxyStarted(result.relay, result.port, restarted = true)
                    is VeilProxy.StartResult.Failed -> Event.ProxyStartFailed(result.relay, result.reason)
                },
            )
        }
    }

    /** Interface switch, VPN on/off, loss of network: the route starts over from the mode. */
    private fun watchNetwork() {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        var last: Network? = cm.activeNetwork
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (network == last) return
                last = network
                events.post(Event.NetworkPathChanged(reachable = true, censored = false))
            }

            override fun onLost(network: Network) {
                if (network != last) return
                last = null
                events.post(Event.NetworkPathChanged(reachable = false, censored = false))
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = Unit
        })
    }

    private fun VeilMode.toRouteMode() = when (this) {
        VeilMode.OFF -> TransportRoute.Mode.OFF
        VeilMode.AUTO -> TransportRoute.Mode.AUTO
        VeilMode.ON -> TransportRoute.Mode.ON
    }

    private companion object {
        const val TAG = "Transport"
    }
}
