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

    /** payload hex → expected intake key hex, expected address hex. */
    private val vectors = listOf(
        Triple("0a20101112131415161718191a1b1c1d1e1f202122232425262728292a2b2c2d2e2f1220a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebf", "101112131415161718191a1b1c1d1e1f202122232425262728292a2b2c2d2e2f", "a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebf"),
        Triple("1220a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebf", null, "a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebf"),
        Triple("0a20101112131415161718191a1b1c1d1e1f202122232425262728292a2b2c2d2e2f", "101112131415161718191a1b1c1d1e1f202122232425262728292a2b2c2d2e2f", null),
        Triple("101112131415161718191a1b1c1d1e1f202122232425262728292a2b2c2d2e2f", "101112131415161718191a1b1c1d1e1f202122232425262728292a2b2c2d2e2f", null),
    )

    /** Mutation: drop the 32-byte legacy branch in `read` — the legacy case reddens. */
    @Test
    fun `every payload reads as the vector says`() {
        for ((payload, key, address) in vectors) {
            val card = ContactCardPayload.read(hexDecode(payload))
            assertNotNull(payload, card)
            assertArrayEquals(payload, key?.let(::hexDecode), card!!.intakeKey)
            assertArrayEquals(payload, address?.let(::hexDecode), card.accountAddress)
        }
    }

    @Test
    fun `an address-only card encodes as the vector`() {
        val (payload, _, address) = vectors.first { it.second == null && it.third != null }
        assertEquals(payload, hexEncode(ContactCardPayload(accountAddress = hexDecode(address!!)).encoded()))
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
