package com.construct.messenger.data.api

import com.construct.messenger.transport.TransportRoute
import io.grpc.Status
import java.net.ConnectException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a stream over VEIL says the local proxy is gone (TODO 102). */
class LocalProxyGoneTest {
    private val veil = TransportRoute.Target.Veil(port = 43913, relay = "relay.example:443")
    private val refused = Status.UNAVAILABLE.withCause(ConnectException("Connection refused")).asException()

    /**
     * `veil_is_alive` can say "alive" with the listener closed (iOS after a suspension); a refused
     * loopback connection must rotate anyway. Mutation: ask only `proxyAlive` — reddens.
     */
    @Test
    fun `a refused loopback connection is a dead proxy whatever is_alive says`() {
        assertTrue(localProxyGone(refused, veil) { true })
    }

    @Test
    fun `any other failure asks is_alive`() {
        val reset = Status.UNAVAILABLE.withDescription("connection reset").asException()
        assertFalse(localProxyGone(reset, veil) { true })
        assertTrue(localProxyGone(reset, veil) { false })
        assertFalse(localProxyGone(null, veil) { true })
    }

    @Test
    fun `a direct stream never has a proxy to lose`() {
        assertFalse(localProxyGone(refused, TransportRoute.Target.Direct) { false })
    }
}
