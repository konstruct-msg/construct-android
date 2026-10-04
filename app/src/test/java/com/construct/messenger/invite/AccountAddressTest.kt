package com.construct.messenger.invite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.recovery.RecoveryRepository
import com.construct.messenger.recovery.RecoveryStatus
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

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

    // Own address against the server's fingerprint (TODO 109; iOS `029dc156`).

    @Test
    fun `a stored address the server confirms is ours`() {
        assertEquals(AccountAddress.OwnVerdict.CONFIRMED, AccountAddress.verdict(key, serverFingerprint(key)))
    }

    /** The iOS Mac that had been its own account: it held that account's key, the server named the
     * account it had since joined. Mutation: treat a mismatch as unconfirmed — the old key ships. */
    @Test
    fun `a stored address of another account is foreign`() {
        val other = ByteArray(32) { (255 - it).toByte() }
        assertEquals(AccountAddress.OwnVerdict.FOREIGN, AccountAddress.verdict(other, serverFingerprint(key)))
    }

    @Test
    fun `nothing to compare with is neither confirmed nor foreign`() {
        assertEquals(AccountAddress.OwnVerdict.UNCONFIRMED, AccountAddress.verdict(key, null))
        assertEquals(AccountAddress.OwnVerdict.ABSENT, AccountAddress.verdict(null, serverFingerprint(key)))
        assertEquals(AccountAddress.OwnVerdict.ABSENT, AccountAddress.verdict(byteArrayOf(1, 2), serverFingerprint(key)))
    }

    private fun own(stored: ByteArray?, status: () -> RecoveryStatus): Pair<OwnAccountAddress, KeystoreManager> {
        val keystore = mock<KeystoreManager>()
        whenever(keystore.getOwnAccountAddress()).thenReturn(stored)
        val recovery = mock<RecoveryRepository>()
        runBlocking { whenever(recovery.status()).doAnswer { status() } }
        return OwnAccountAddress(keystore, recovery) to keystore
    }

    /** Mutation: return the stored key without asking the server — the foreign key ships. */
    @Test
    fun `a card carries a foreign address never, and it is deleted`() = runBlocking {
        val (own, keystore) = own(ByteArray(32) { 0x7E }) { RecoveryStatus(true, serverFingerprint(key)) }
        assertNull(own.forCard())
        verify(keystore).deleteOwnAccountAddress()
    }

    @Test
    fun `an unreachable server leaves the address out and keeps it`() = runBlocking {
        val (own, keystore) = own(key) { throw IOException("down") }
        assertNull(own.forCard())
        own.checkBeforeInvite()
        verify(keystore, never()).deleteOwnAccountAddress()
    }

    @Test
    fun `a confirmed address goes into the card, asked once`() = runBlocking {
        var asked = 0
        val (own, _) = own(key) { asked++; RecoveryStatus(true, serverFingerprint(key)) }
        assertTrue(key.contentEquals(own.forCard()))
        assertTrue(key.contentEquals(own.forCard()))
        assertEquals(1, asked)
    }
}
