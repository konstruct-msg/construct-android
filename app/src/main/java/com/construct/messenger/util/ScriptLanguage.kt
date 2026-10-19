package com.construct.messenger.util

/**
 * The language a piece of text's own script names, for the font to take the right forms of the
 * Han characters Chinese, Japanese and Korean share. Without one, Android draws them in the forms
 * of the device's languages: Japanese text on a device without Japanese came out in Chinese
 * forms, or Korean ones with Korean ahead (owner, 2026-10-19).
 *
 * Kana is Japanese and Hangul Korean; kana wins over Hangul. Han alone, or anything else, names
 * nothing, and the device's choice stands. **Canon:** iOS `ScriptLanguage` (construct-ios #281).
 */
object ScriptLanguage {
    fun of(text: CharSequence): String? {
        var hangul = false
        for (c in text) {
            when (c.code) {
                in 0x3041..0x30FF, in 0x31F0..0x31FF, in 0xFF66..0xFF9D -> return JAPANESE
                in 0x1100..0x11FF, in 0x3131..0x318E, in 0xAC00..0xD7A3 -> hangul = true
            }
        }
        return if (hangul) KOREAN else null
    }

    const val JAPANESE = "ja"
    const val KOREAN = "ko"
}
