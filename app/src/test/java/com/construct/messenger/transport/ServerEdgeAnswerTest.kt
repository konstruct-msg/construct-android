package com.construct.messenger.transport

import com.construct.messenger.transport.TransportRoute.Effect
import com.construct.messenger.transport.TransportRoute.Event
import com.construct.messenger.transport.TransportRoute.Mode
import com.construct.messenger.transport.TransportRoute.RpcFailure
import com.construct.messenger.transport.TransportRoute.State
import com.construct.messenger.transport.TransportRoute.Target
import io.grpc.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The server's edge answering 5xx is not the path's failure — canon iOS
 * `VeilFailurePolicyTests.testA5xxFromTheServersEdgeIsNotAPathFailure`. 2026-11-21 18:00 UTC the
 * server's Caddy answered 502 while the messaging service was down; on iOS three of them demoted a
 * healthy front. Each test names the mutation that must redden it.
 */
class ServerEdgeAnswerTest {
    private val veil = Target.Veil(7000, "front#a:443")

    /** What grpc-java gives an HTTP response with no grpc-status: the code from the HTTP status, "HTTP status code N". */
    private fun edge(code: Status.Code, http: Int, extra: String = "") =
        Status.fromCode(code).withDescription("HTTP status code $http$extra")

    /**
     * 502/503/504 and 500 are the edge answering; a 4xx and a dropped connection keep their old
     * meaning. Mutations: drop the 5xx rule, or accept a 4xx — each reddens.
     */
    @Test
    fun aServers5xxIsNotThePathsFailure() {
        assertEquals(RpcFailure.SERVER_NOT_READY, RouteObservingInterceptor.classify(edge(Status.Code.UNAVAILABLE, 502, "\ninvalid content-type: text/html"), veil))
        assertEquals(RpcFailure.SERVER_NOT_READY, RouteObservingInterceptor.classify(edge(Status.Code.UNAVAILABLE, 503), Target.Direct))
        assertEquals(RpcFailure.SERVER_NOT_READY, RouteObservingInterceptor.classify(edge(Status.Code.UNKNOWN, 500), veil))
        assertFalse(RpcFailure.SERVER_NOT_READY.isTransport)
        assertFalse(RpcFailure.SERVER_NOT_READY.blamesFront)

        assertEquals(RpcFailure.APPLICATION_ERROR, RouteObservingInterceptor.classify(edge(Status.Code.UNIMPLEMENTED, 404), veil))
        assertEquals(RpcFailure.TRANSPORT_UNKNOWN, RouteObservingInterceptor.classify(Status.UNAVAILABLE.withDescription("connection lost"), veil))
        assertNull(RouteObservingInterceptor.edgeHttpStatus("connection lost"))
        assertNull(RouteObservingInterceptor.edgeHttpStatus(null))
    }

    /**
     * The incident end to end: past the soft-fail threshold of edge 502s the front stays active
     * and is not demoted; on the direct path they count nothing toward VEIL. Mutation: make the
     * kind a transport failure — the front is demoted and the direct path escalates.
     */
    @Test
    fun edge502sNeitherDemoteTheFrontNorEscalateTheDirectPath() {
        val kind = RouteObservingInterceptor.classify(edge(Status.Code.UNAVAILABLE, 502), veil)
        val config = TransportRoute.Config()
        val active = State.VeilActive(veil.relay, veil.port, sinceMs = 0)
        var state: State = active
        val effects = mutableListOf<Effect>()
        repeat(10) {
            val out = TransportRoute.reduce(state, Event.RpcFailed(kind, veil, foreground = true), Mode.ON, config, nowMs = 60_000)
            state = out.state
            effects += out.effects
        }
        assertEquals(active, state)
        assertFalse(effects.any { it is Effect.DemoteRelay })

        var direct: State = State.Direct(0)
        repeat(10) {
            direct = TransportRoute.reduce(direct, Event.RpcFailed(kind, Target.Direct, foreground = true), Mode.AUTO, config, nowMs = 60_000).state
        }
        assertEquals(State.Direct(0), direct)
    }
}
