package com.construct.messenger.data.repository

import android.content.Context
import com.construct.messenger.data.model.AppTheme
import com.construct.messenger.data.model.Appearance
import com.construct.messenger.data.model.ChatFace
import com.construct.messenger.data.model.TextSize
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Settings → Appearance: theme, message face, message size.
 *
 * SharedPreferences rather than DataStore because the activity needs the theme before its first
 * frame; an asynchronous read would paint the default theme and then switch. Keys are iOS's
 * `@AppStorage` names (`appTheme`, `chatTextFace`, `textSize`).
 */
@Singleton
class AppearanceRepository @Inject constructor(
    @param:ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val state = MutableStateFlow(
        Appearance(
            theme = AppTheme.from(prefs.getString(KEY_THEME, null)),
            chatFace = ChatFace.from(prefs.getString(KEY_FACE, null)),
            textSize = TextSize.from(prefs.getString(KEY_SIZE, null)),
        ),
    )
    val appearance: StateFlow<Appearance> = state.asStateFlow()

    fun setTheme(theme: AppTheme) {
        prefs.edit().putString(KEY_THEME, theme.raw).apply()
        state.update { it.copy(theme = theme) }
    }

    fun setChatFace(face: ChatFace) {
        prefs.edit().putString(KEY_FACE, face.raw).apply()
        state.update { it.copy(chatFace = face) }
    }

    fun setTextSize(size: TextSize) {
        prefs.edit().putString(KEY_SIZE, size.raw).apply()
        state.update { it.copy(textSize = size) }
    }

    private companion object {
        const val PREFS = "appearance_prefs"
        const val KEY_THEME = "appTheme"
        const val KEY_FACE = "chatTextFace"
        const val KEY_SIZE = "textSize"
    }
}
