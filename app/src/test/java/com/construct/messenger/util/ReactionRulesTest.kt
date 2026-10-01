package com.construct.messenger.util

import com.construct.messenger.util.ReactionRules.Decision
import com.construct.messenger.util.ReactionRules.Incoming
import com.construct.messenger.util.ReactionRules.Row
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import shared.proto.messaging.v1.Content.MessageContent
import shared.proto.messaging.v1.Content.ReactionAction

/** Port of iOS `ReactionReducerTests` and `ReactionWireTests`. */
class ReactionRulesTest {

    /**
     * `android.icu` is a stub in a JVM test; the JDK 21 running it has the same Unicode tables.
     * Reflection, because the compile classpath is `android.jar`, which lacks these methods.
     */
    private object Jdk : ReactionRules.EmojiProperties {
        private val emoji = Character::class.java.getMethod("isEmoji", Int::class.javaPrimitiveType)
        private val presentation = Character::class.java.getMethod("isEmojiPresentation", Int::class.javaPrimitiveType)
        override fun isEmoji(codePoint: Int) = emoji.invoke(null, codePoint) as Boolean
        override fun isEmojiPresentation(codePoint: Int) = presentation.invoke(null, codePoint) as Boolean
    }

    private fun valid(s: String) = ReactionRules.isValidEmoji(s, Jdk)

    @Test fun `add on nothing sets`() =
        assertEquals(Decision.Set("❤️", 10), ReactionRules.apply(null, Incoming.Add("❤️"), 10, "m"))

    @Test fun `a newer add replaces`() =
        assertEquals(Decision.Set("🔥", 20), ReactionRules.apply(Row("❤️", 10), Incoming.Add("🔥"), 20, "m"))

    @Test fun `an older add and a tie keep what is there`() {
        assertEquals(Decision.KeepExisting, ReactionRules.apply(Row("❤️", 10), Incoming.Add("🔥"), 9, "m"))
        assertEquals(Decision.KeepExisting, ReactionRules.apply(Row("❤️", 10), Incoming.Add("🔥"), 10, "m"))
    }

    @Test fun `a newer remove clears, a remove of nothing does nothing`() {
        assertEquals(Decision.Clear, ReactionRules.apply(Row("❤️", 10), Incoming.Remove, 11, "m"))
        assertEquals(Decision.KeepExisting, ReactionRules.apply(null, Incoming.Remove, 11, "m"))
    }

    @Test fun `an empty target or an unreadable action is invalid`() {
        assertEquals(Decision.DropInvalid, ReactionRules.apply(null, Incoming.Add("❤️"), 1, " "))
        assertEquals(Decision.DropInvalid, ReactionRules.apply(null, null, 1, "m"))
        assertEquals(Decision.DropInvalid, ReactionRules.apply(null, Incoming.Add("❤️"), 1, "x".repeat(65)))
    }

    @Test fun `actions map as on iOS`() {
        assertEquals(Incoming.Add("😂"), ReactionRules.incoming(0, "😂", Jdk))
        assertEquals(Incoming.Add("😂"), ReactionRules.incoming(1, "😂", Jdk))
        assertNull(ReactionRules.incoming(1, "", Jdk))
        assertEquals(Incoming.Remove, ReactionRules.incoming(2, "whatever", Jdk))
        assertNull(ReactionRules.incoming(7, "😂", Jdk))
    }

    /** iOS device log 2026-08-21: a peer's reaction arrived as `set(emoji: "H")`. */
    @Test fun `a letter or a word is not a reaction`() {
        assertFalse(valid("H"))
        assertNull(ReactionRules.incoming(1, "H", Jdk))
        assertFalse(valid("pwned"))
        assertFalse(valid("❤️❤️"))
        assertFalse(valid("❤️ "))
    }

    @Test fun `a digit is not a reaction but its keycap is`() {
        assertFalse(valid("3"))
        assertFalse(valid("#"))
        assertTrue(valid("3️⃣"))
    }

    @Test fun `every shape of real emoji is accepted`() {
        ReactionRules.QUICK_SET.forEach { assertTrue(it, valid(it)) }
        assertTrue(valid("👍🏽"))
        assertTrue(valid("👨‍👩‍👧"))
        assertTrue(valid("🇷🇺"))
    }

    @Test fun `empty and whitespace are refused`() {
        assertFalse(valid(""))
        assertFalse(valid(" "))
        assertFalse(valid("\n"))
    }

    @Test fun `a repeat tap removes, another replaces, the first adds`() {
        assertEquals(Incoming.Remove, ReactionRules.localToggle("❤️", "❤️", Jdk))
        assertEquals(Incoming.Add("🔥"), ReactionRules.localToggle("❤️", "🔥", Jdk))
        assertEquals(Incoming.Add("❤️"), ReactionRules.localToggle(null, "❤️", Jdk))
        assertEquals("❤️", ReactionRules.LIKE)
    }

    @Test fun `a zero clock falls back, a set one does not`() {
        assertEquals(500, ReactionRules.normalizeTimestamp(0, 500))
        assertEquals(42, ReactionRules.normalizeTimestamp(42, 500))
    }

    @Test fun `an orphan goes after seven days, a reaction on a stored message never`() {
        val week = ReactionRules.ORPHAN_TTL_MS
        assertFalse(ReactionRules.shouldEvictOrphan(targetExists = false, receivedAtMs = 0, nowMs = week - 1))
        assertTrue(ReactionRules.shouldEvictOrphan(targetExists = false, receivedAtMs = 0, nowMs = week))
        assertFalse(ReactionRules.shouldEvictOrphan(targetExists = true, receivedAtMs = 0, nowMs = week * 2))
    }

    @Test fun `the wire names the lower-cased target, the action and the clock`() {
        val add = MessageContent.parseFrom(ReactionWire.encode("ABC-1", Incoming.Add("🔥"), 1234)).reaction
        assertEquals("abc-1", add.targetMessageId)
        assertEquals("🔥", add.emoji)
        assertEquals(ReactionAction.REACTION_ACTION_ADD, add.action)
        assertEquals(1234, add.timestampMs)
        val remove = MessageContent.parseFrom(ReactionWire.encode("abc-1", Incoming.Remove, 0)).reaction
        assertEquals(ReactionAction.REACTION_ACTION_REMOVE, remove.action)
        assertEquals("", remove.emoji)
        assertEquals(0, remove.timestampMs)
    }

    @Test fun `a received reaction decodes as one, not as a bubble`() {
        val decoded = IncomingPlaintext.decode(ReactionWire.encode("abc-1", Incoming.Add("😂"), 77))
        assertFalse(decoded.isUserVisible)
        assertEquals(IncomingPlaintext.Reaction("abc-1", 1, "😂", 77), decoded.reaction)
    }
}
