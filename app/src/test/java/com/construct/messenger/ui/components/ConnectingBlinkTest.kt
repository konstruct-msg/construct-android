package com.construct.messenger.ui.components

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Every value is a frame. Mutations: a shorter half-period (an animation again), no change of
 * state (a dot that does not blink).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectingBlinkTest {
    @Test
    fun `the connecting dot changes once a second`() = runTest {
        val seen = mutableListOf<Boolean>()
        val job = launch { connectingBlink().toList(seen) }
        advanceTimeBy(10_001)
        job.cancel()
        assertEquals(List(11) { it % 2 == 1 }, seen)
    }
}
