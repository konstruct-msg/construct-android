package com.construct.messenger.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.intl.LocaleList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The style carries the text's language, which is what picks Noto Sans CJK's JP or KR forms. */
class ScriptLocaleTest {
    @Test
    fun japaneseTextIsTaggedJa() {
        assertEquals(LocaleList("ja"), TextStyle().forScriptOf("直してね").localeList)
    }

    @Test
    fun koreanTextIsTaggedKo() {
        assertEquals(LocaleList("ko"), TextStyle().forScriptOf("직접 해요").localeList)
    }

    @Test
    fun otherTextKeepsTheStyleAsItWas() {
        val style = TextStyle(localeList = null)
        assertNull(style.forScriptOf("直骨").localeList)
        assertEquals(style, style.forScriptOf("hello"))
    }
}
