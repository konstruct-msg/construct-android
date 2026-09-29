package com.construct.messenger.data.model

/**
 * What the reader chose in Settings → Appearance.
 *
 * **Canon:** iOS `AppearanceSettingsView` (`AppTheme`, `TextSize`) and `ConstructTheme.swift` →
 * `ChatTextPreference`. Stored values are the iOS raw strings, so the two apps describe the same
 * choice with the same word.
 */
data class Appearance(
    val theme: AppTheme = AppTheme.DARK,
    val chatFace: ChatFace = ChatFace.SYSTEM,
    val textSize: TextSize = TextSize.STANDARD,
)

enum class AppTheme(val raw: String) {
    AUTOMATIC("automatic"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        fun from(raw: String?) = entries.firstOrNull { it.raw == raw } ?: DARK
    }
}

/**
 * Typeface of message text — bubbles and the composer that fills them — and nothing else.
 * Monospace is the language of the chrome; what a person writes and reads is content.
 * [SYSTEM] is the default, as on iOS since 2026-09-21.
 */
enum class ChatFace(val raw: String) {
    SYSTEM("system"),
    MONO("mono");

    companion object {
        fun from(raw: String?) = entries.firstOrNull { it.raw == raw } ?: SYSTEM
    }
}

/** Multiplier for message text only; the chrome keeps its size. iOS `ChatTextPreference.sizeMultiplier`. */
enum class TextSize(val raw: String, val multiplier: Float) {
    COMPACT("compact", 0.88f),
    STANDARD("standard", 1.0f),
    LARGE("large", 1.18f);

    companion object {
        fun from(raw: String?) = entries.firstOrNull { it.raw == raw } ?: STANDARD
    }
}
