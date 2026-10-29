package com.construct.messenger.stealth

import com.construct.messenger.stealth.IntakeCredentials.Published
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntakePublishingTest {
    private val account = "6f1c2a9e-0b7d-4c55-9a1e-3d2f8b7c6a10"
    private val key = Published.keyId(ByteArray(32) { 1 })
    private fun published(epoch: ULong, keyId: String = key, accountId: String = account) =
        Published(accountId, epoch, keyId)

    /** The current epoch is in it: a fresh install's first incoming message is due today. */
    @Test
    fun `the window is today and six days ahead`() {
        assertEquals((100uL..106uL).toList(), IntakeCredentials.Publishing.window(100uL))
    }

    /** Mutation: publish on every call — an authenticated RPC an hour and on every reconnect; never — the window drains. */
    @Test
    fun `publish once per epoch, and again if the clock went back`() {
        assertTrue(IntakeCredentials.Publishing.shouldPublish(null, published(100uL)))
        assertFalse(IntakeCredentials.Publishing.shouldPublish(published(100uL), published(100uL)))
        assertTrue(IntakeCredentials.Publishing.shouldPublish(published(99uL), published(100uL)))
        assertTrue(IntakeCredentials.Publishing.shouldPublish(published(101uL), published(100uL)))
    }

    /** Mutation: compare the epoch alone — the defect, a re-minted key's tags waiting a day. */
    @Test
    fun `a new key publishes in the same epoch`() {
        val other = Published.keyId(ByteArray(32) { 2 })
        assertNotEquals(key, other)
        assertTrue(IntakeCredentials.Publishing.shouldPublish(published(100uL), published(100uL, keyId = other)))
    }

    /** Mutation: drop the account from the comparison — another account here would inherit the marker. */
    @Test
    fun `another account publishes, the same one in another case does not`() {
        val other = "0a0b0c0d-0000-4000-8000-000000000001"
        assertTrue(IntakeCredentials.Publishing.shouldPublish(published(100uL), published(100uL, accountId = other)))
        assertFalse(
            IntakeCredentials.Publishing.shouldPublish(published(100uL), published(100uL, accountId = account.uppercase())),
        )
    }

    /** Mutation: decode a malformed marker as something — an unreadable one must mean "publish". */
    @Test
    fun `the marker survives its own encoding, and a malformed one reads as none`() {
        assertEquals(published(100uL), Published.decode(published(100uL).encoded()))
        assertNull(Published.decode(null))
        assertNull(Published.decode("100"))
        assertNull(Published.decode("$account|100"))
        assertNull(Published.decode("$account|x|$key"))
    }
}
