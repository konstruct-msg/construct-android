package com.construct.messenger.util

/**
 * `android.icu` is a stub in a JVM test; the JDK 21 running it has the same Unicode tables.
 * Reflection, because the compile classpath is `android.jar`, which lacks these methods.
 */
internal object JdkEmoji : ReactionRules.EmojiProperties {
    private val emoji = Character::class.java.getMethod("isEmoji", Int::class.javaPrimitiveType)
    private val presentation = Character::class.java.getMethod("isEmojiPresentation", Int::class.javaPrimitiveType)
    override fun isEmoji(codePoint: Int) = emoji.invoke(null, codePoint) as Boolean
    override fun isEmojiPresentation(codePoint: Int) = presentation.invoke(null, codePoint) as Boolean
}
