package com.construct.messenger.util

import android.annotation.SuppressLint
import android.icu.lang.UCharacter
import android.icu.lang.UProperty
import java.text.BreakIterator

/**
 * What a reaction may be and which of two wins. Pure: no Room, no clock — the timestamp is an
 * argument, so last-write-wins is the same in a test as on a phone.
 *
 * **Canon:** iOS `ReactionReducer`. A reaction is metadata on a target message, never a chat row
 * (`decisions/reactions-instagram-model.md`): one per reactor per message, a repeat tap on the same
 * emoji removes it, and the later `timestamp_ms` wins — a tie keeps what is there.
 */
object ReactionRules {

    /** Instagram DM quick set, in display order. The first is the double-tap like. */
    val QUICK_SET = listOf("❤️", "😂", "😮", "😢", "😠", "🔥")
    val LIKE: String get() = QUICK_SET.first()

    const val ORPHAN_TTL_MS = 7L * 24 * 60 * 60 * 1000
    private const val MAX_EMOJI_UTF8_BYTES = 32
    private const val MAX_TARGET_ID_LENGTH = 64

    sealed interface Incoming {
        data class Add(val emoji: String) : Incoming
        data object Remove : Incoming
    }

    sealed interface Decision {
        data class Set(val emoji: String, val timestampMs: Long) : Decision
        data object Clear : Decision
        /** Older, a tie, or a remove of nothing. */
        data object KeepExisting : Decision
        /** Malformed. The envelope is still acknowledged, so it cannot come back as a bubble. */
        data object DropInvalid : Decision
    }

    data class Row(val emoji: String, val timestampMs: Long)

    /** Field 4 `timestamp_ms`; 0 is a peer from before the field, so [fallbackMs] stands in. */
    fun normalizeTimestamp(payloadMs: Long, fallbackMs: Long): Long =
        if (payloadMs > 0) payloadMs else maxOf(0, fallbackMs)

    fun isValidTargetId(id: String): Boolean {
        val trimmed = id.trim()
        return trimmed.isNotEmpty() && trimmed.length <= MAX_TARGET_ID_LENGTH
    }

    /**
     * One grapheme, and one Unicode calls emoji. iOS learned why both halves matter: a letter
     * typed into the "more" screen arrived on the far side as `set(emoji: "H")`, a peer's text
     * drawn into our transcript. A bare `3` carries the emoji property (keycaps start with it) but
     * does not present as emoji, so a single code point must have emoji presentation; a longer
     * cluster (skin tone, ZWJ family, keycap, flag) counts when its first code point is emoji.
     */
    fun isValidEmoji(emoji: String, props: EmojiProperties = EmojiProperties.Icu): Boolean {
        if (emoji.isEmpty() || emoji.toByteArray(Charsets.UTF_8).size > MAX_EMOJI_UTF8_BYTES) return false
        val graphemes = BreakIterator.getCharacterInstance().apply { setText(emoji) }
        if (graphemes.next() != emoji.length) return false
        val first = emoji.codePointAt(0)
        if (!props.isEmoji(first)) return false
        return emoji.codePointCount(0, emoji.length) > 1 || props.isEmojiPresentation(first)
    }

    /** Proto `ReactionAction`: 1 add, 2 remove; 0 with a valid emoji is a forgetful add. */
    fun incoming(actionRawValue: Int, emoji: String, props: EmojiProperties = EmojiProperties.Icu): Incoming? =
        when (actionRawValue) {
            1, 0 -> if (isValidEmoji(emoji, props)) Incoming.Add(emoji) else null
            2 -> Incoming.Remove
            else -> null
        }

    fun apply(existing: Row?, incoming: Incoming?, timestampMs: Long, targetMessageId: String): Decision {
        if (!isValidTargetId(targetMessageId) || incoming == null || timestampMs < 0) return Decision.DropInvalid
        if (existing == null) {
            return when (incoming) {
                is Incoming.Add -> Decision.Set(incoming.emoji, timestampMs)
                Incoming.Remove -> Decision.KeepExisting
            }
        }
        if (timestampMs <= existing.timestampMs) return Decision.KeepExisting
        return when (incoming) {
            is Incoming.Add -> Decision.Set(incoming.emoji, timestampMs)
            Incoming.Remove -> Decision.Clear
        }
    }

    /** Tapping the emoji already set takes it off; any other replaces it. */
    fun localToggle(current: String?, tapped: String, props: EmojiProperties = EmojiProperties.Icu): Incoming? {
        if (!isValidEmoji(tapped, props)) return null
        return if (current == tapped) Incoming.Remove else Incoming.Add(tapped)
    }

    fun shouldEvictOrphan(targetExists: Boolean, receivedAtMs: Long, nowMs: Long): Boolean =
        !targetExists && nowMs - receivedAtMs >= ORPHAN_TTL_MS

    /**
     * The two Unicode properties the test needs. ICU on a phone; the JDK's own tables in a JVM
     * test, where `android.icu` is a stub that answers false.
     */
    interface EmojiProperties {
        fun isEmoji(codePoint: Int): Boolean
        fun isEmojiPresentation(codePoint: Int): Boolean

        object Icu : EmojiProperties {
            // The constants are compile-time ints; Android 8's ICU 58 already knows them (ICU 57+).
            @SuppressLint("InlinedApi")
            override fun isEmoji(codePoint: Int) = UCharacter.hasBinaryProperty(codePoint, UProperty.EMOJI)

            @SuppressLint("InlinedApi")
            override fun isEmojiPresentation(codePoint: Int) =
                UCharacter.hasBinaryProperty(codePoint, UProperty.EMOJI_PRESENTATION)
        }
    }
}
