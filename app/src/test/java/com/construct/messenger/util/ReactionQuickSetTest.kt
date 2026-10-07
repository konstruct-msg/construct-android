package com.construct.messenger.util

import android.content.SharedPreferences
import com.construct.messenger.data.repository.ReactionQuickSetRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

/**
 * The menu's reaction row becomes the user's own gradually, and without reshuffling.
 * **Canon:** iOS `ReactionQuickSetTests`; each test names the mutation that must redden it.
 */
class ReactionQuickSetTest {

    private fun recording(emoji: List<String>, start: ReactionQuickSet = ReactionQuickSet.INITIAL) =
        emoji.fold(start) { set, e -> set.recording(e) }

    @Test
    fun `a fresh row is the popular set`() {
        assertEquals(ReactionRules.QUICK_SET, ReactionQuickSet.INITIAL.slots)
        assertTrue(ReactionQuickSet.INITIAL.isWellFormed)
    }

    /** Mutation: seed the defaults at 0, or let any score beat the weakest — the one-off enters. */
    @Test
    fun `a new emoji enters only after repeated use`() {
        assertFalse("one use is not a habit", "👍" in recording(listOf("👍")).slots)
        assertFalse("👍" in recording(listOf("👍", "👍")).slots)
        assertTrue("👍" in recording(listOf("👍", "👍", "👍")).slots)
    }

    /** Mutation: sort the row by score — positions move and this reddens. */
    @Test
    fun `an entrant takes the evicted slot and nothing else moves`() {
        val before = ReactionQuickSet.INITIAL.slots
        val after = recording(listOf("👍", "👍", "👍")).slots
        val changed = before.indices.filter { before[it] != after[it] }
        assertEquals(1, changed.size)
        assertEquals("👍", after[changed.single()])
    }

    /** Mutation: seed every default equally — the first tie, ❤️, is evicted first. */
    @Test
    fun `the least popular default gives way first and the like stays first`() {
        val after = recording(listOf("👍", "👍", "👍")).slots
        assertEquals("👍", after.last())
        assertEquals(ReactionRules.LIKE, after.first())
    }

    @Test
    fun `an emoji in use is not evicted`() {
        var set = ReactionQuickSet.INITIAL
        repeat(20) { set = set.recording("😂").recording("👍").recording("🙏").recording("👀") }
        listOf("😂", "👍", "🙏", "👀").forEach { assertTrue(it, it in set.slots) }
        assertTrue(set.isWellFormed)
    }

    /** Mutation: drop the `FORGET_BELOW` filter — the table keeps every emoji ever sent. */
    @Test
    fun `old counts are forgotten`() {
        var set = recording(listOf("🦄"))
        repeat(60) { set = set.recording("❤️") }
        assertNull(set.scores["🦄"])
        assertTrue(set.scores.size <= ReactionQuickSet.SIZE)
    }

    @Test
    fun `the stored row survives a round trip and a malformed one falls back to the popular set`() {
        val learned = recording(listOf("👍", "👍", "👍"))
        val stored = ReactionQuickSetRepository.encode(learned)
        assertEquals(learned, ReactionQuickSetRepository.decode(stored))
        assertEquals(learned.slots, ReactionQuickSetRepository(prefsHolding(stored)).slots.value)

        val malformed = """{"slots":["👍","👍"],"scores":{}}"""
        assertEquals(ReactionRules.QUICK_SET, ReactionQuickSetRepository(prefsHolding(malformed)).slots.value)
        assertEquals(ReactionRules.QUICK_SET, ReactionQuickSetRepository(prefsHolding("not json")).slots.value)
    }

    private fun prefsHolding(raw: String): SharedPreferences =
        mock { on { getString(any(), anyOrNull()) } doReturn raw }

    private fun anyOrNull(): String? = org.mockito.kotlin.anyOrNull()
}
