package com.construct.messenger.recovery

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A failed `SetRecoveryKey` is judged by what the server holds afterwards (testers, 2026-10-03):
 * an answer lost on the way back still set the key, and a retry met ALREADY_EXISTS shown as a
 * connection error; a new phrase never fitted, the first attempt's did.
 */
class AfterFailedSetUpTest {
    private val key = ByteArray(32) { it.toByte() }
    private val fingerprint = key.joinToString("") { "%02x".format(it) }.take(32)

    /** Mutation: report every failure as FAILED — the landed key is shown as an error; reddens. */
    @Test
    fun `a key that landed though the answer was lost is this phrase's`() {
        assertEquals(SetUpOutcome.DONE, afterFailedSetUp(RecoveryStatus(true, fingerprint), key))
    }

    /** Mutation: treat any set key as ours — the device keeps an address that is not the account's. */
    @Test
    fun `a key from another phrase sends the user to the first one`() {
        val other = ByteArray(32) { 7 }
        assertEquals(SetUpOutcome.OTHER_PHRASE_SET, afterFailedSetUp(RecoveryStatus(true, fingerprint), other))
    }

    @Test
    fun `nothing set, or no status, is a failure to retry`() {
        assertEquals(SetUpOutcome.FAILED, afterFailedSetUp(RecoveryStatus(false, null), key))
        assertEquals(SetUpOutcome.FAILED, afterFailedSetUp(RecoveryStatus(true, null), key))
        assertEquals(SetUpOutcome.FAILED, afterFailedSetUp(null, key))
    }
}
