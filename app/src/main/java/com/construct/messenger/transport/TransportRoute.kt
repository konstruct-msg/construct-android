package com.construct.messenger.transport

/**
 * Which path the next RPC takes: the server directly, or a VEIL front. A pure machine —
 * `(state, event, mode, config, now) -> (state, effects)` — no I/O, no clock.
 *
 * **Canon:** iOS `TransportReducer.swift` plus the two rules `TransportRouter.swift` applies around
 * it (escalation only when the mode is not Off; Auto leaves VEIL when a direct call succeeds).
 * Not protocol — two clients deciding differently still read each other's messages — so each
 * platform keeps its own, and both run construct-protos `conformance/transport_route.json`
 * (`TransportRouteVectorsTest`). Decision: `transport-route-per-platform-shared-vectors.md`.
 *
 * Times are Unix milliseconds.
 */
object TransportRoute {

    enum class Mode { OFF, AUTO, ON }

    sealed interface Target {
        data object Direct : Target
        data class Veil(val port: Int, val relay: String) : Target
    }

    enum class StreamMethod { QUIC, H2, VEIL }

    enum class StreamFailure(val midSession: Boolean) {
        OPEN_TIMEOUT(false),
        MID_SESSION_TIMEOUT(true),
        WRITE_FAILED(true),
        CLOSED(false),
        TRANSPORT_UNKNOWN(false),
        MID_SESSION_CLOSED(true),
        MID_SESSION_UNKNOWN(true),
    }

    enum class RpcFailure(val isTransport: Boolean, val isHardRelay: Boolean) {
        TRANSIENT_CANCELLATION(false, false),
        AUTH_REJECTED(false, false),
        APPLICATION_ERROR(false, false),
        TLS_FINGERPRINT_BLOCKED(true, true),
        TLS_CERT_EXPIRED(true, true),
        WEB_TUNNEL_BLOCKED(true, true),
        STALE_LOCAL_PROXY(true, true),
        STREAM_TIMEOUT(true, false),
        TRANSPORT_UNKNOWN(true, false),
    }

    sealed interface State {
        data object Offline : State
        data class Direct(val consecutiveFails: Int) : State
        data object VeilProbing : State
        data class VeilActive(val relay: String, val port: Int, val sinceMs: Long) : State
        data class VeilCooldown(val untilMs: Long) : State
    }

    sealed interface Event {
        data class RpcSucceeded(val via: Target, val latencyMs: Int) : Event
        /** [foreground]: a user-visible call; background maintenance does not move the path. */
        data class RpcFailed(val failure: RpcFailure, val via: Target, val foreground: Boolean) : Event
        data class StreamOpened(val method: StreamMethod, val via: Target) : Event
        data class StreamFailed(val method: StreamMethod, val failure: StreamFailure, val via: Target) : Event
        data class NetworkPathChanged(val reachable: Boolean, val censored: Boolean) : Event
        /** The mode changed; the new one is the `mode` argument. */
        data class VeilModeChanged(val censored: Boolean) : Event
        data object VeilConfigChanged : Event
        data object BackgroundWake : Event
        data class ProxyStarted(val relay: String, val port: Int, val restarted: Boolean) : Event
        data class ProxyStartFailed(val relay: String?, val reason: String) : Event
        data object CooldownElapsed : Event
        data object ManualReset : Event
    }

    sealed interface Effect {
        data object InvalidateGrpcClient : Effect
        /** Point gRPC at this local port, or at the server (null). */
        data class SetVeilPort(val port: Int?) : Effect
        /** Start the proxy and answer with [Event.ProxyStarted] or [Event.ProxyStartFailed]. */
        data object RequestProxyStart : Effect
        data object RequestProxyStop : Effect
        /** Post [Event.CooldownElapsed] at this time. */
        data class ScheduleCooldownEnd(val atMs: Long) : Effect
    }

    data class Config(
        val directFailThreshold: Int = 2,
        /**
         * A stream that established and then died counts in full: the counter it feeds is cleared
         * by every successful call and open, which keep happening on the network that kills
         * established streams (iOS, 2026-08-11; this device, 2026-09-29). Counted as one, it never
         * reached the threshold.
         */
        val midSessionDeathWeight: Int = 2,
        val allowDirectToVeilEscalation: Boolean = true,
        /** The only backoff after a failed `veil_start`. */
        val veilCooldownMs: Long = 30_000,
    )

    data class Outcome(val state: State, val effects: List<Effect>)

    /** Where a fresh process starts. Auto never pre-activates VEIL from geography. */
    fun initial(mode: Mode, @Suppress("UNUSED_PARAMETER") censored: Boolean, reachable: Boolean): State = when {
        !reachable -> State.Offline
        mode == Mode.ON -> State.VeilProbing
        else -> State.Direct(0)
    }

    fun reduce(state: State, event: Event, mode: Mode, config: Config = Config(), nowMs: Long): Outcome {
        val effective = config.copy(
            allowDirectToVeilEscalation = config.allowDirectToVeilEscalation && mode != Mode.OFF,
        )
        val outcome = reduceInner(state, event, mode, effective, nowMs)
        // Auto: a direct call that succeeds while VEIL is up means VEIL was a false positive.
        if (mode == Mode.AUTO && state is State.VeilActive && event is Event.RpcSucceeded && event.via !is Target.Veil) {
            val effects = outcome.effects.toMutableList()
            for (e in listOf(Effect.RequestProxyStop, Effect.SetVeilPort(null))) if (e !in effects) effects += e
            return Outcome(State.Direct(0), effects)
        }
        return outcome
    }

    private val TEARDOWN = listOf(Effect.RequestProxyStop, Effect.SetVeilPort(null), Effect.InvalidateGrpcClient)
    private val ROTATE = listOf(Effect.RequestProxyStop, Effect.SetVeilPort(null), Effect.RequestProxyStart, Effect.InvalidateGrpcClient)

    private fun reduceInner(state: State, event: Event, mode: Mode, config: Config, nowMs: Long): Outcome {
        // Events that apply whatever the state.
        when (event) {
            is Event.NetworkPathChanged -> {
                if (!event.reachable) return Outcome(State.Offline, TEARDOWN)
                val next = initial(mode, event.censored, reachable = true)
                return Outcome(next, if (next == State.VeilProbing) TEARDOWN + Effect.RequestProxyStart else TEARDOWN)
            }
            Event.ManualReset ->
                return if (state == State.Direct(0)) Outcome(state, listOf(Effect.InvalidateGrpcClient))
                else Outcome(State.Direct(0), TEARDOWN)
            is Event.VeilModeChanged -> return modeChanged(state, mode)
            // Active: replace the proxy. Anything else: a push or a manifest must not move a
            // working direct path onto a relay, and a probe in flight finishes on its own.
            Event.VeilConfigChanged, Event.BackgroundWake ->
                return if (state is State.VeilActive) Outcome(State.VeilProbing, ROTATE) else Outcome(state, emptyList())
            else -> Unit
        }
        return when (state) {
            State.Offline -> Outcome(state, emptyList())
            is State.Direct -> direct(state.consecutiveFails, event, config)
            State.VeilProbing -> probing(event, config, nowMs)
            is State.VeilActive -> active(state, event)
            is State.VeilCooldown ->
                if (event == Event.CooldownElapsed) Outcome(State.Direct(0), listOf(Effect.InvalidateGrpcClient))
                else Outcome(state, emptyList())
        }
    }

    private fun modeChanged(state: State, mode: Mode): Outcome {
        // Offline: an attempt would fail on DNS into a cooldown; the mode is reapplied on return.
        if (state == State.Offline) return Outcome(state, emptyList())
        return when (mode) {
            Mode.OFF -> Outcome(State.Direct(0), TEARDOWN)
            Mode.ON ->
                if (state is State.VeilActive || state == State.VeilProbing) Outcome(state, emptyList())
                else Outcome(State.VeilProbing, listOf(Effect.RequestProxyStart))
            // Auto stops forcing and lets outcomes decide; it never starts VEIL by itself.
            Mode.AUTO -> Outcome(state, emptyList())
        }
    }

    private fun direct(fails: Int, event: Event, config: Config): Outcome {
        fun stay(f: Int) = Outcome(State.Direct(f), emptyList())
        return when (event) {
            is Event.RpcSucceeded -> stay(0)
            is Event.RpcFailed ->
                if (!event.foreground || event.via is Target.Veil || !event.failure.isTransport) stay(fails)
                else countFailure(fails, config, 1)
            is Event.StreamOpened -> if (event.via is Target.Veil) stay(fails) else stay(0)
            is Event.StreamFailed ->
                if (event.via is Target.Veil) stay(fails)
                else countFailure(fails, config, if (event.failure.midSession) config.midSessionDeathWeight else 1)
            // Every way into Direct stops the proxy, so a start landing here raced past that stop
            // (VEIL turned off mid-handshake). Adopting it would undo an explicit Off.
            is Event.ProxyStarted -> Outcome(State.Direct(fails), listOf(Effect.RequestProxyStop, Effect.SetVeilPort(null)))
            else -> stay(fails)
        }
    }

    private fun countFailure(fails: Int, config: Config, weight: Int): Outcome {
        val next = fails + maxOf(1, weight)
        if (config.allowDirectToVeilEscalation && next >= config.directFailThreshold) {
            return Outcome(State.VeilProbing, listOf(Effect.RequestProxyStart, Effect.InvalidateGrpcClient))
        }
        return Outcome(State.Direct(next), emptyList())
    }

    private fun probing(event: Event, config: Config, nowMs: Long): Outcome = when (event) {
        is Event.ProxyStarted -> Outcome(
            State.VeilActive(event.relay, event.port, nowMs),
            listOfNotNull(Effect.SetVeilPort(event.port), Effect.InvalidateGrpcClient.takeIf { event.restarted }),
        )
        is Event.ProxyStartFailed -> {
            val until = nowMs + config.veilCooldownMs
            Outcome(State.VeilCooldown(until), listOf(Effect.SetVeilPort(null), Effect.ScheduleCooldownEnd(until)))
        }
        // The proxy is not up; RPC outcomes say nothing about it.
        else -> Outcome(State.VeilProbing, emptyList())
    }

    private fun active(state: State.VeilActive, event: Event): Outcome = when {
        event is Event.RpcFailed && event.foreground && event.via is Target.Veil && event.failure.isTransport ->
            // Hard: the relay is observably broken, rotate. Soft: the tunnel may live; reconnect.
            if (event.failure.isHardRelay) Outcome(State.VeilProbing, ROTATE)
            else Outcome(state, listOf(Effect.InvalidateGrpcClient))
        // A stream death alone does not prove the relay is burned.
        event is Event.StreamFailed && event.via is Target.Veil -> Outcome(state, listOf(Effect.InvalidateGrpcClient))
        else -> Outcome(state, emptyList())
    }
}
