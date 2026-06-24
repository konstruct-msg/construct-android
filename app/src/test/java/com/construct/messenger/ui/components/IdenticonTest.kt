package com.construct.messenger.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IdenticonTest {

    /**
     * Golden 64-bit FNV-1a hashes — the cross-platform deterministic value. iOS
     * `IdenticonView.patternHash` computes the same hash over the seed's UTF-8 bytes,
     * so if these break, identicons would render differently on Android vs iOS.
     */
    private val goldenHashes = mapOf(
        "alice-123" to 0xef11_1976_7f09_37eauL,
        "construct" to 0xa300_68dc_b17d_42f2uL,
        "550e8400-e29b-41d4-a716-446655440000" to 0xfbb0_538e_e83a_5048uL,
    )

    @Test
    fun patternHashMatchesIosGolden() {
        goldenHashes.forEach { (seed, expected) ->
            assertEquals("seed=$seed", expected, identiconPatternHash(seed))
        }
    }

    @Test
    fun gridIsHorizontallySymmetric() {
        for (seed in goldenHashes.keys) {
            val grid = identiconCells(seed)
            val cols = grid[0].size
            for (r in grid.indices) {
                for (c in 0 until cols) {
                    assertEquals(
                        "seed=$seed r=$r c=$c not mirrored",
                        grid[r][c],
                        grid[r][cols - 1 - c],
                    )
                }
            }
        }
    }

    @Test
    fun gridIsDeterministic() {
        val a = identiconCells("stable-id")
        val b = identiconCells("stable-id")
        for (r in a.indices) {
            assertTrue("row $r differs", a[r].contentEquals(b[r]))
        }
    }
}
