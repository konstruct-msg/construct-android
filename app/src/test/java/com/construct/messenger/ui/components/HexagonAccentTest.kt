package com.construct.messenger.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class HexagonAccentTest {

    /**
     * Golden hues derived from the iOS `Color.hexagonAccent(for:)` djb2 algorithm.
     * The hue (`hash % 360`) is the cross-platform deterministic value; if these
     * break, avatars would be tinted differently on Android vs iOS.
     */
    private val golden = mapOf(
        "alice-123" to 318,
        "bob-456" to 116,
        "carol-789" to 67,
        "dave-000" to 90,
        "550e8400-e29b-41d4-a716-446655440000" to 232,
        "construct" to 210,
    )

    @Test
    fun matchesIosGoldenHues() {
        golden.forEach { (userId, expected) ->
            assertEquals("userId=$userId", expected, hexagonHue(userId))
        }
    }

    @Test
    fun isDeterministic() {
        assertEquals(hexagonHue("stable-id"), hexagonHue("stable-id"))
    }
}
