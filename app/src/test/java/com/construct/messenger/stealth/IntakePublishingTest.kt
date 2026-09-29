package com.construct.messenger.stealth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntakePublishingTest {

    /** The current epoch is in it: a fresh install's first incoming message is due today. */
    @Test
    fun `the window is today and six days ahead`() {
        assertEquals((100uL..106uL).toList(), IntakeCredentials.Publishing.window(100uL))
    }

    /** Mutation: publish on every start — an authenticated RPC on a hot path; never — the window drains. */
    @Test
    fun `publish once per epoch, and again if the clock went back`() {
        assertTrue(IntakeCredentials.Publishing.shouldPublish(null, 100uL))
        assertFalse(IntakeCredentials.Publishing.shouldPublish(100uL, 100uL))
        assertTrue(IntakeCredentials.Publishing.shouldPublish(99uL, 100uL))
        assertTrue(IntakeCredentials.Publishing.shouldPublish(101uL, 100uL))
    }
}
