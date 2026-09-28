package com.construct.messenger.stealth

import com.construct.messenger.invite.hexDecode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IssuerKeyPinTest {

    private val pin = hexDecode("cc5432dbd95825657c02424fb726b7fcffb1952fedf5b0c9a875ae186e6a2933")
    private val points = List(3) { ByteArray(32) { b -> (it + b).toByte() } }
    private val proof = byteArrayOf(1)

    private fun check(
        evaluated: List<ByteArray> = points,
        serverPubkey: ByteArray = pin,
        dleqProof: ByteArray = proof,
        version: Int = 1,
        dleq: (List<ByteArray>, List<ByteArray>, ByteArray, ByteArray) -> Boolean = { _, _, _, _ -> true },
    ) = IssuerKeyPin.check(3, points, evaluated, serverPubkey, dleqProof, version, dleq)

    /** Same bytes as iOS `issuerKeyPins[1]`; the two clients carry one key. */
    @Test
    fun `version 1 is pinned to the key iOS pins`() {
        assertArrayEquals(pin, IssuerKeyPin.pinned(1))
    }

    /** Mutation: verify against the echoed serverPubkey — the issuer could key-tag per user. */
    @Test
    fun `the proof is checked against the pin, not the key the response names`() {
        var checkedAgainst: ByteArray? = null
        check(serverPubkey = ByteArray(0)) { _, _, _, k -> checkedAgainst = k; true }
        assertArrayEquals(pin, checkedAgainst)
    }

    @Test
    fun `a response naming another key is refused`() {
        assertTrue(check(serverPubkey = ByteArray(32) { 7 }) is IssuerKeyPin.Verdict.Reject)
    }

    /** Mutation: skip the DLEQ call when the proof is empty — a pinned key would prove nothing. */
    @Test
    fun `a pinned key with no proof is refused`() {
        assertTrue(check(dleqProof = ByteArray(0)) is IssuerKeyPin.Verdict.Reject)
    }

    @Test
    fun `a failing proof is refused`() {
        assertTrue(check { _, _, _, _ -> false } is IssuerKeyPin.Verdict.Reject)
    }

    @Test
    fun `an unpinned version is accepted unchecked so rotation does not stop issuance`() {
        var called = false
        val verdict = check(version = 99) { _, _, _, _ -> called = true; false }
        assertEquals(IssuerKeyPin.Verdict.Accept(3, dleqChecked = false), verdict)
        assertTrue(!called)
    }

    /**
     * The issuer grants min(asked, room). Refusing a short batch stranded the room left under the
     * hourly cap — Android did that until 2026-09-28.
     */
    @Test
    fun `a short batch is the rest of the cap and is finalized, proof over the matching prefix`() {
        var blindedSeen = 0
        val verdict = check(evaluated = points.take(2)) { b, _, _, _ -> blindedSeen = b.size; true }
        assertEquals(IssuerKeyPin.Verdict.Accept(2, dleqChecked = true), verdict)
        assertEquals(2, blindedSeen)
    }

    @Test
    fun `more points than asked, or none, is refused`() {
        assertTrue(check(evaluated = points + points) is IssuerKeyPin.Verdict.Reject)
        assertTrue(check(evaluated = emptyList()) is IssuerKeyPin.Verdict.Reject)
    }
}
