package com.construct.messenger.transport

import com.construct.messenger.transport.TransportRoute.Effect
import com.construct.messenger.transport.TransportRoute.Event
import com.construct.messenger.transport.TransportRoute.Mode
import com.construct.messenger.transport.TransportRoute.State
import com.construct.messenger.transport.TransportRoute.StreamFailure
import com.construct.messenger.transport.TransportRoute.StreamMethod
import com.construct.messenger.transport.TransportRoute.Target
import org.junit.Assert.assertEquals
import org.junit.Test

/** Under a VPN, Auto stays direct; without one, and under On, nothing changes. */
class RouteUnderVpnTest {
    private val streamDied = Event.StreamFailed(StreamMethod.H2, StreamFailure.MID_SESSION_TIMEOUT, Target.Direct)

    /** The tester's case: one direct stream dies mid-session. Mutation: ignoring the flag — VEIL. */
    @Test
    fun autoUnderVpnStaysDirectWhenAStreamDies() {
        val outcome = TransportRoute.reduce(State.Direct(0), streamDied, Mode.AUTO, routeConfig(underVpn = true), nowMs = 0)

        assertEquals(State.Direct(2), outcome.state)
        assertEquals(emptyList<Effect>(), outcome.effects)
    }

    /** The rule against DPI that kills established streams still holds without a VPN. */
    @Test
    fun autoWithoutVpnStillEscalates() {
        val outcome = TransportRoute.reduce(State.Direct(0), streamDied, Mode.AUTO, routeConfig(underVpn = false), nowMs = 0)

        assertEquals(State.VeilProbing, outcome.state)
    }

    /** On never goes direct, VPN or not. */
    @Test
    fun onUnderVpnStillGoesToTheFront() {
        val outcome = TransportRoute.reduce(State.Direct(0), Event.ManualReset, Mode.ON, routeConfig(underVpn = true), nowMs = 0)

        assertEquals(State.VeilProbing, outcome.state)
    }
}
