package com.construct.messenger.invite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** **Canon:** iOS `AccountAddressTests`. */
class AccountAddressTest {

    private val key = ByteArray(32) { it.toByte() }

    /** The form the server's `UserId::parse` reads. */
    @Test
    fun `the wire form is ed25519 colon lowercase hex`() {
        assertEquals(
            "ed25519:000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",
            AccountAddress.wire(key),
        )
    }

    @Test
    fun `a recipient is named by address when one is known`() {
        assertEquals(AccountAddress.wire(key), AccountAddress.recipientField("id", key))
    }

    @Test
    fun `without a usable address the account id is kept`() {
        assertEquals("id", AccountAddress.recipientField("id", null))
        assertEquals("id", AccountAddress.recipientField("id", ByteArray(31)))
    }

    /** The server's `key_fingerprint`: leading hex, uppercase, groups of four. */
    private fun serverFingerprint(k: ByteArray) =
        hexEncode(k).uppercase().take(32).chunked(4).joinToString(" ")

    @Test
    fun `the servers fingerprint of our key matches`() {
        assertTrue(AccountAddress.matchesServerFingerprint(key, serverFingerprint(key)))
    }

    /** Mutation: return true unconditionally — a foreign phrase would then set our address. */
    @Test
    fun `another keys fingerprint does not match`() {
        assertFalse(AccountAddress.matchesServerFingerprint(key, serverFingerprint(ByteArray(32) { 0x7E })))
    }

    @Test
    fun `a truncated fingerprint is not enough`() {
        assertFalse(AccountAddress.matchesServerFingerprint(key, "0001 0203"))
        assertFalse(AccountAddress.matchesServerFingerprint(key, ""))
    }
}
