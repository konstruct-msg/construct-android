package com.construct.messenger.veil

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Failure-ordered front selection and the ≥2-usable floor. Pure, so no keystore or network.
 * **Canon:** iOS `VeilFrontFloorTests` + `RelayPool` ordering.
 */
class VeilFrontPoolTest {

    private val pin = "ab".repeat(32)
    private fun front(addr: String, learnedAtMs: Long) = LearnedFront(addr, addr.substringBefore(':'), pin, learnedAtMs)

    private val a = front("a.example:443", learnedAtMs = 300)
    private val b = front("b.example:443", learnedAtMs = 200)
    private val c = front("c.example:443", learnedAtMs = 100)

    // MARK: order

    @Test
    fun `with no failures, newest first`() {
        val ordered = VeilFrontPool.order(listOf(c, a, b), failures = emptyMap())
        assertEquals(listOf(a.address, b.address, c.address), ordered.map { it.address })
    }

    @Test
    fun `a failed front sinks below the ones that have not failed`() {
        // a is newest but has failed twice; b and c (no failures) lead, a trails.
        val ordered = VeilFrontPool.order(listOf(a, b, c), failures = mapOf(a.address to 2))
        assertEquals(listOf(b.address, c.address, a.address), ordered.map { it.address })
    }

    @Test
    fun `fewer failures beats more failures`() {
        val ordered = VeilFrontPool.order(listOf(a, b, c), failures = mapOf(a.address to 3, b.address to 1))
        // c (0) < b (1) < a (3)
        assertEquals(listOf(c.address, b.address, a.address), ordered.map { it.address })
    }

    // MARK: usableCount / floor

    @Test
    fun `usable counts only fronts with a live capability`() {
        val live = setOf(a.address, c.address) // b has none
        val n = VeilFrontPool.usableCount(listOf(a, b, c).map { it.address }) { it in live }
        assertEquals(2, n)
    }

    @Test
    fun `a just-failed front is excluded even with a live capability`() {
        val n = VeilFrontPool.usableCount(listOf(a, b).map { it.address }, excluding = setOf(a.address)) { true }
        assertEquals(1, n)
    }

    @Test
    fun `duplicates and blanks do not inflate the count`() {
        val n = VeilFrontPool.usableCount(listOf(a.address, a.address, "")) { true }
        assertEquals(1, n)
    }

    @Test
    fun `floor is two`() {
        assertEquals(2, VeilFrontPool.FLOOR)
    }

    @Test
    fun `below-floor boundary`() {
        assertTrue(VeilFrontPool.isBelowFloor(0))
        assertTrue(VeilFrontPool.isBelowFloor(1))
        assertFalse(VeilFrontPool.isBelowFloor(2))
        assertFalse(VeilFrontPool.isBelowFloor(3))
    }

    /** The target scenario: two saved fronts, one down → one usable remains → below floor, but still
     *  one front to carry the top-up request. */
    @Test
    fun `one of two down is below floor but still reachable`() {
        val n = VeilFrontPool.usableCount(listOf(a, b).map { it.address }, excluding = setOf(a.address)) { true }
        assertTrue(VeilFrontPool.isBelowFloor(n))
        assertTrue(n >= 1)
    }
}
