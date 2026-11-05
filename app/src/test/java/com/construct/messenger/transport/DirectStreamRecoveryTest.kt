package com.construct.messenger.transport

import com.construct.messenger.transport.TransportRoute.Effect
import com.construct.messenger.transport.TransportRoute.Event
import com.construct.messenger.transport.TransportRoute.Mode
import com.construct.messenger.transport.TransportRoute.State
import com.construct.messenger.transport.TransportRoute.StreamFailure
import com.construct.messenger.transport.TransportRoute.StreamMethod
import com.construct.messenger.transport.TransportRoute.Target
import com.construct.messenger.data.api.GrpcClient
import com.construct.messenger.veil.VeilMode
import com.construct.messenger.veil.VeilProxy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

class DirectStreamRecoveryTest {
    /**
     * OnePlus, 2026-11-05 14:00:58..14:02:48 UTC: Auto under VPN stayed Direct after a
     * silent stream, but four subsequent streams timed out on the same channel. Off forced a fresh one.
     * The socket, rather than just its HTTP/2 stream, must be replaced.
     */
    @Test
    fun aSilentDirectStreamUnderVpnGetsAFreshChannelWithoutStartingVeil() {
        for ((failure, fails) in listOf(StreamFailure.MID_SESSION_TIMEOUT to 2, StreamFailure.OPEN_TIMEOUT to 1)) {
            val event = Event.StreamFailed(StreamMethod.H2, failure, Target.Direct)
            val outcome = routeOutcome(State.Direct(0), event, Mode.AUTO, underVpn = true, nowMs = 0)

            assertEquals(State.Direct(fails), outcome.state)
            assertEquals(listOf(Effect.InvalidateGrpcClient), outcome.effects)
        }
    }

    @Test
    fun offAlsoReplacesASilentDirectConnection() {
        val event = Event.StreamFailed(StreamMethod.H2, StreamFailure.MID_SESSION_TIMEOUT, Target.Direct)
        val outcome = routeOutcome(State.Direct(0), event, Mode.OFF, underVpn = false, nowMs = 0)

        assertEquals(State.Direct(2), outcome.state)
        assertEquals(listOf(Effect.InvalidateGrpcClient), outcome.effects)
    }

    @Test
    fun theRouterActuallyReplacesTheClientAfterADirectTimeout() = runBlocking<Unit> {
        val modes = MutableStateFlow(VeilMode.OFF)
        val veil: VeilProxy = mock { on { modeState } doReturn modes }
        val grpc: GrpcClient = mock()
        val router = TransportRouter(mock(), veil, grpc, TransportEvents())

        router.send(Event.StreamFailed(StreamMethod.H2, StreamFailure.MID_SESSION_TIMEOUT, Target.Direct))

        assertEquals(State.Direct(2), router.route.value)
        verify(grpc).reconnect()
        verify(veil, never()).start()
    }

    @Test
    fun withoutVpnTheSameTimeoutStillStartsVeilAndInvalidatesOnlyOnce() {
        val event = Event.StreamFailed(StreamMethod.H2, StreamFailure.MID_SESSION_TIMEOUT, Target.Direct)
        val outcome = routeOutcome(State.Direct(0), event, Mode.AUTO, underVpn = false, nowMs = 0)

        assertEquals(State.VeilProbing, outcome.state)
        assertEquals(listOf(Effect.RequestProxyStart, Effect.InvalidateGrpcClient), outcome.effects)
    }

    @Test
    fun closedStreamsAndOldVeilFailuresDoNotReplaceADirectConnection() {
        for (event in listOf(
            Event.StreamFailed(StreamMethod.H2, StreamFailure.MID_SESSION_CLOSED, Target.Direct),
            Event.StreamFailed(StreamMethod.VEIL, StreamFailure.OPEN_TIMEOUT, Target.Veil(40000, "front")),
        )) {
            val outcome = routeOutcome(State.Direct(0), event, Mode.AUTO, underVpn = true, nowMs = 0)
            assertEquals(emptyList<Effect>(), outcome.effects)
        }
    }

    @Test
    fun lateDirectTimeoutDoesNotRestartAProbeOrAnOfflineRoute() {
        val event = Event.StreamFailed(StreamMethod.H2, StreamFailure.MID_SESSION_TIMEOUT, Target.Direct)
        for (state in listOf(State.VeilProbing, State.Offline)) {
            val outcome = routeOutcome(state, event, Mode.AUTO, underVpn = true, nowMs = 0)
            assertEquals(state, outcome.state)
            assertEquals(emptyList<Effect>(), outcome.effects)
        }
    }
}
