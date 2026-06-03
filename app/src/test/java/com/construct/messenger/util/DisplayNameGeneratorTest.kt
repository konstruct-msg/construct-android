package com.construct.messenger.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayNameGeneratorTest {

    /**
     * Golden vectors derived directly from the iOS source word lists
     * (`DisplayNameGenerator.swift`). If any of these break, Android and iOS would
     * generate different anonymous names for the same user — a cross-platform bug.
     */
    private val golden = mapOf(
        "550e8400-e29b-41d4-a716-446655440000" to "cool cobra",
        "14f28d31-0000-0000-0000-000000000000" to "silver raven",
        "test" to "ancient sphinx",
        "construct" to "idle deer",
        "6f5e37ac9b2d4e1f8a0c5d6e7f80911a" to "gleaming serpent",
    )

    @Test
    fun matchesIosGoldenVectors() {
        golden.forEach { (userId, expected) ->
            assertEquals("userId=$userId", expected, DisplayNameGenerator.generate(userId))
        }
    }

    @Test
    fun isDeterministic() {
        val userId = "any-stable-id"
        assertEquals(
            DisplayNameGenerator.generate(userId),
            DisplayNameGenerator.generate(userId),
        )
    }

    @Test
    fun isLowercaseTwoWords() {
        val name = DisplayNameGenerator.generate("550e8400-e29b-41d4-a716-446655440000")
        assertEquals(name, name.lowercase())
        assertEquals(2, name.split(" ").size)
    }

    @Test
    fun shortIdIsSixHexChars() {
        val id = DisplayNameGenerator.generateShortId("construct")
        assertTrue(id.matches(Regex("[0-9a-f]{6}")))
    }
}
