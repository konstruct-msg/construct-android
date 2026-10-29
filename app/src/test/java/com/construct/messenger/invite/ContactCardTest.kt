package com.construct.messenger.invite

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Type 27 against construct-protos `conformance/knst_contact_card.json` — iOS reads the same file.
 * Values copied from it; if it changes, copy them again.
 */
class ContactCardTest {

    private data class Vector(val payload: String, val key: String?, val address: String?, val generation: UInt)

    private val key = "101112131415161718191a1b1c1d1e1f202122232425262728292a2b2c2d2e2f"
    private val address = "a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebf"
    private val vectors = listOf(
        Vector("0a20${key}1220$address", key, address, 0u),
        Vector("0a20${key}1220${address}18ac02", key, address, 300u),
        Vector("1220$address", null, address, 0u),
        Vector("0a20$key", key, null, 0u),
        Vector(key, key, null, 0u),
    )

    /** Mutation: drop the 32-byte legacy branch in `read`, or the generation — a case reddens. */
    @Test
    fun `every payload reads as the vector says`() {
        for (v in vectors) {
            val card = ContactCardPayload.read(hexDecode(v.payload))
            assertNotNull(v.payload, card)
            assertArrayEquals(v.payload, v.key?.let(::hexDecode), card!!.intakeKey)
            assertArrayEquals(v.payload, v.address?.let(::hexDecode), card.accountAddress)
            assertEquals(v.payload, v.generation, card.intakeGeneration)
        }
    }

    /** Mutation: leave the generation off the wire — the 300 case (two varint bytes) reddens. */
    @Test
    fun `cards encode as the vectors`() {
        for (v in vectors.filter { it.payload != key }) {
            val card = ContactCardPayload(v.key?.let(::hexDecode), v.address?.let(::hexDecode), v.generation)
            assertEquals(v.payload, hexEncode(card.encoded()))
        }
    }

    private val a = ByteArray(32) { 0xA1.toByte() }
    private val b = ByteArray(32) { 0xB2.toByte() }

    @Test
    fun `the first address is pinned`() {
        assertEquals(AccountAddressPin.PINNED, AccountAddressPin.decide(null, a, AccountAddressSource.CARD))
        assertEquals(AccountAddressPin.UNCHANGED, AccountAddressPin.decide(a, a, AccountAddressSource.CARD))
    }

    /** Mutation: let a card replace the pinned address — this reddens. */
    @Test
    fun `a card never replaces a pinned address`() {
        val outcome = AccountAddressPin.decide(a, b, AccountAddressSource.CARD)
        assertEquals(AccountAddressPin.CONFLICT_KEPT, outcome)
        assertTrue(outcome.isSecurityEvent)
    }

    @Test
    fun `an invite replaces and still raises the event`() {
        val outcome = AccountAddressPin.decide(a, b, AccountAddressSource.INVITE)
        assertEquals(AccountAddressPin.CONFLICT_REPLACED, outcome)
        assertTrue(outcome.isSecurityEvent)
    }
}
