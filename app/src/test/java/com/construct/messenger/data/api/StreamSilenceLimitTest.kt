package com.construct.messenger.data.api

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A connection that has not answered yet is given less time than one that was working: the server
 * answers Subscribe at once, so a minute of silence on a new connection is a swallowed one
 * (Portal WG, 2026-10-22). Mutation: one limit for both — reddens.
 */
class StreamSilenceLimitTest {
    @Test
    fun aNewConnectionGetsTwentySecondsAWorkingOneAMinute() {
        assertEquals(20_000L, silenceLimitMs(framesThisConnection = 0))
        assertEquals(60_000L, silenceLimitMs(framesThisConnection = 1))
        assertEquals(60_000L, silenceLimitMs(framesThisConnection = 500))
    }
}
