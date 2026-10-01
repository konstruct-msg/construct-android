package com.construct.messenger.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uniffi.construct_core.ReorderStats

class ReorderDiagnosticsTest {
    private fun stats(decrypted: ULong = 5uL, evicted: ULong = 0uL) = ReorderStats(
        decrypted = decrypted,
        previousEpoch = 2uL,
        olderEpoch = 1uL,
        maxEpochLag = 2u,
        maxSkipDepth = 7u,
        evictedEpochFailures = evicted,
    )

    @Test
    fun `the line is iOS's, field for field`() {
        assertEquals(
            "REORDER decrypted=5 prev_epoch=2 older_epoch=1 max_lag=2 max_skip=7 evicted=0",
            ReorderDiagnostics.line(stats()),
        )
    }

    @Test
    fun `written when the counters move, not on every sample`() {
        var current: ReorderStats? = null
        val diagnostics = ReorderDiagnostics { current }
        assertNull(diagnostics.sample())
        current = stats()
        assertEquals(ReorderDiagnostics.line(stats()), diagnostics.sample())
        assertNull(diagnostics.sample())
        current = stats(decrypted = 6uL)
        assertEquals(ReorderDiagnostics.line(stats(decrypted = 6uL)), diagnostics.sample())
    }
}
