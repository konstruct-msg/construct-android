package com.construct.messenger.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every call goes through TURN only, over TLS with VEIL On where offered, and never falls back to
 * a direct call (owner, 2026-10-16; the rule is the core's `callRelayUrls` since 0.76.0). Canon:
 * iOS `CallIcePlanTests`. These hold the bridge and the relay-only policy this side applies.
 */
class CallIcePlanTest {

    private val turn = listOf(
        "turn:t.example:3478?transport=udp",
        "turn:t.example:3478?transport=tcp",
        "turns:t.example:5349?transport=tcp",
    )

    /** Mutation: `relayOnly = false`, or drop the TLS filter — this fails. */
    @Test
    fun `with VEIL on the call is relayed over TLS`() {
        val plan = CallIcePlan.make(turn, "u", "c", veilOn = true)!!
        assertTrue("no direct candidates, no peer address", plan.relayOnly)
        assertEquals(listOf("turns:t.example:5349?transport=tcp"), plan.servers.flatMap { it.urls })
        assertEquals("u", plan.servers.single().username)
    }

    @Test
    fun `with VEIL on and no TLS offered it is still relay only`() {
        val plain = turn.take(2)
        val plan = CallIcePlan.make(plain, "u", "c", veilOn = true)!!
        assertTrue(plan.relayOnly)
        assertEquals(plain, plan.servers.flatMap { it.urls })
    }

    /**
     * Until 0.76.0 VEIL off went direct here: the first candidate, a private host address, failed
     * the iPhone's relay-only check within a second (2026-11-16). Mutation: `relayOnly = !veilOn`.
     */
    @Test
    fun `without VEIL the call is still relay only over every TURN url`() {
        val plan = CallIcePlan.make(turn, "u", "c", veilOn = false)!!
        assertTrue("a direct call hands the peer our address", plan.relayOnly)
        assertEquals("plain TURN kept for latency without VEIL", turn, plan.servers.flatMap { it.urls })
    }

    /** A STUN fallback is a direct call; no call is better than that one. Mutation: bring it back. */
    @Test
    fun `with no TURN there is no call`() {
        assertNull(CallIcePlan.make(emptyList(), "", "", veilOn = true))
        assertNull(CallIcePlan.make(emptyList(), "", "", veilOn = false))
    }
}
