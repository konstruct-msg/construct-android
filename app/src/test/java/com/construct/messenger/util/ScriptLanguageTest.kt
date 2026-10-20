package com.construct.messenger.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The script names the language: kana ja, Hangul ko, kana first; Han alone names none (iOS #281). */
class ScriptLanguageTest {
    @Test
    fun kanaIsJapanese() {
        assertEquals("ja", ScriptLanguage.of("直してください"))
        assertEquals("ja", ScriptLanguage.of("カタカナ"))
        assertEquals("ja", ScriptLanguage.of("ｶﾀｶﾅ"))
        assertEquals("ja", ScriptLanguage.of("ㇰ"))
    }

    @Test
    fun hangulIsKorean() {
        assertEquals("ko", ScriptLanguage.of("안녕하세요 直"))
        assertEquals("ko", ScriptLanguage.of("ㄱ"))
        assertEquals("ko", ScriptLanguage.of("ᄀ"))
    }

    @Test
    fun kanaWinsOverHangul() {
        assertEquals("ja", ScriptLanguage.of("안녕 こんにちは"))
    }

    @Test
    fun hanAloneOrAnythingElseNamesNothing() {
        assertNull(ScriptLanguage.of("直骨角"))
        assertNull(ScriptLanguage.of("привет, hello"))
        assertNull(ScriptLanguage.of(""))
    }

    /** The ends of every range, and one past each. */
    @Test
    fun theRangesAreInclusive() {
        for (c in listOf(0x3041, 0x30FF, 0x31F0, 0x31FF, 0xFF66, 0xFF9D)) assertEquals("ja", ScriptLanguage.of(c.toChar().toString()))
        for (c in listOf(0x1100, 0x11FF, 0x3131, 0x318E, 0xAC00, 0xD7A3)) assertEquals("ko", ScriptLanguage.of(c.toChar().toString()))
        for (c in listOf(0x3040, 0x31EF, 0xFF65, 0xFF9E, 0x10FF, 0x1200, 0x3130, 0x318F, 0xABFF, 0xD7A4)) assertNull(ScriptLanguage.of(c.toChar().toString()))
    }
}
