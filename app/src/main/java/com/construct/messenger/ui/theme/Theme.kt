package com.construct.messenger.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = CTColor.accentDark,
    secondary = CTColor.accentDimDark,
    tertiary = CTColor.danger,
    background = CTColor.bgDark,
    surface = CTColor.bgMsgDark,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = CTColor.textDark,
    onSurface = CTColor.textDark
)

private val LightColorScheme = lightColorScheme(
    // The light halves, like every other token in this scheme. They were the dark ones while the
    // accent had a single value; it does not any more.
    primary = CTColor.accentLight,
    secondary = CTColor.accentDimLight,
    tertiary = CTColor.danger,
    background = CTColor.bgLight,
    surface = CTColor.bgMsgLight,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = CTColor.textLight,
    onSurface = CTColor.textLight
)

val LocalIsDarkTheme = staticCompositionLocalOf { true }

/** Message face and size from Settings → Appearance; read by [CTFont.message]. */
val LocalChatText = staticCompositionLocalOf { ChatText() }

/**
 * @param darkTheme Also switches the `CTColor` tokens ([CTColor.isDark]); the app root passes
 *   the resolved Settings → Appearance choice.
 */
@Composable
fun KonstructMessengerTheme(
    darkTheme: Boolean = true,
    chatText: ChatText = ChatText(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    // Written before any child reads a token, so the first frame is already in the right theme.
    if (CTColor.isDark != darkTheme) CTColor.isDark = darkTheme

    CompositionLocalProvider(LocalIsDarkTheme provides darkTheme, LocalChatText provides chatText) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}