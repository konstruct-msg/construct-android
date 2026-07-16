package com.construct.messenger.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = CTColor.accent,
    secondary = CTColor.accentDim,
    tertiary = CTColor.danger,
    background = CTColor.bg,
    surface = CTColor.bgMsg,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = CTColor.text,
    onSurface = CTColor.text
)

private val LightColorScheme = lightColorScheme(
    primary = CTColor.accent,
    secondary = CTColor.accentDim,
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

@Composable
fun KonstructMessengerTheme(
    // ponytail: light theme not wired — CT* components use dark tokens directly
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    CompositionLocalProvider(LocalIsDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}