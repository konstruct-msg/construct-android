package com.construct.messenger.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * With VEIL On a call goes through TURN only, over TLS where offered, and never falls back to a
 * direct call (vault `decisions/veil-on-never-goes-direct.md`). Canon: iOS `CallIcePlanTests`.
 */
class CallIcePlanTest {

    private val turn = listOf(
        "turn:t.example:3478?transport=udp",
        "turn:t.example:3478?transport=tcp",
        "turns:t.example:5349?transport=tcp",
    )

    /** Mutation: `relayOnly = false` under On, or drop the TLS filter — this fails. */
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

    /** A STUN fallback is a direct call; with VEIL on no call is better than that one. */
    @Test
    fun `with VEIL on and no TURN there is no call`() {
        assertNull(CallIcePlan.make(emptyList(), "", "", veilOn = true))
    }

    @Test
    fun `without VEIL on nothing changes`() {
        val plan = CallIcePlan.make(turn, "u", "c", veilOn = false)!!
        assertFalse(plan.relayOnly)
        assertEquals(turn, plan.servers.flatMap { it.urls })

        val fallback = CallIcePlan.make(emptyList(), "", "", veilOn = false)!!
        assertEquals(listOf(CallIcePlan.OWN_STUN), fallback.servers.flatMap { it.urls })
        assertFalse(fallback.servers.flatMap { it.urls }.any { "google" in it || "cloudflare" in it })
    }
}
