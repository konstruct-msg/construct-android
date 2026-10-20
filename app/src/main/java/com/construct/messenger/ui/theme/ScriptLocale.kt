package com.construct.messenger.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.intl.LocaleList
import com.construct.messenger.util.ScriptLanguage

/**
 * [this] tagged with the language [text]'s script names ([ScriptLanguage]), so the system's Noto
 * Sans CJK draws it in its Japanese or Korean forms; untagged when the script names none.
 */
fun TextStyle.forScriptOf(text: CharSequence): TextStyle =
    ScriptLanguage.of(text)?.let { copy(localeList = LocaleList(it)) } ?: this
