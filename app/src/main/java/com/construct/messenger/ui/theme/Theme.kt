package com.construct.messenger.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/*
 * Material roles filled from the CT palette — step 1 of the Material 3 move
 * (`docs/MATERIAL3_MIGRATION.md`). Every role is set: one left out falls back to the library's
 * baseline purple, and a Material component drawn in it is the one thing on screen that is not
 * ours. The values are today's CT ones, so nothing changes on screen; the designer's file replaces
 * them later, here and nowhere else, because the unsuffixed `CTColor` tokens read these roles.
 *
 * CT has one raised level (`bgMsg`, the card) and the dialog/menu fill (`outMsgBg`), so the surface
 * ladder is two steps, not five: Lowest is the ground, Low…High the dialog fill (Material puts
 * menus on Container and dialogs on High), Highest the card (Material's `Card` and filled field).
 *
 * Role pairs are measured by `ColorSchemeContrastTest`.
 */
private fun ctColorScheme(dark: Boolean): ColorScheme {
    fun pick(d: Color, l: Color) = if (dark) d else l
    val bg = pick(CTColor.bgDark, CTColor.bgLight)
    val card = pick(CTColor.bgMsgDark, CTColor.bgMsgLight)
    val fill = pick(CTColor.outMsgBgDark, CTColor.outMsgBgLight)
    val text = pick(CTColor.textDark, CTColor.textLight)
    val textDim = pick(CTColor.textDimDark, CTColor.textDimLight)
    val accent = pick(CTColor.accentDark, CTColor.accentLight)
    val accentDim = pick(CTColor.accentDimDark, CTColor.accentDimLight)
    val warning = pick(CTColor.warningDark, CTColor.warningLight)
    val noise = pick(CTColor.noiseDark, CTColor.noiseLight)
    // The base only supplies the fixed-accent roles, which no component of the app draws.
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        // The accent. Its label is the ground, as `CTButton` draws it: white on the dark accent
        // reads 3.39, the ground 5.87.
        primary = accent,
        onPrimary = bg,
        // Pressed / tonal: the dim accent, white on it (4.50 dark, 8.23 light).
        primaryContainer = accentDim,
        onPrimaryContainer = CTColor.onFill,
        inversePrimary = pick(CTColor.accentLight, CTColor.accentDark),
        secondary = accentDim,
        onSecondary = CTColor.onFill,
        secondaryContainer = card,
        onSecondaryContainer = text,
        // Warning, not danger: danger is `error`.
        tertiary = warning,
        onTertiary = pick(CTColor.bgDark, CTColor.textLight),
        tertiaryContainer = card,
        onTertiaryContainer = text,
        background = bg,
        onBackground = text,
        surface = bg,
        onSurface = text,
        surfaceVariant = card,
        onSurfaceVariant = textDim,
        // CT is flat: no tint laid over a raised surface.
        surfaceTint = bg,
        inverseSurface = text,
        inverseOnSurface = bg,
        error = CTColor.dangerFixed,
        onError = CTColor.onFill,
        errorContainer = card,
        onErrorContainer = text,
        // Fields and outlined controls need 3:1 against the ground; `noise` is a hairline and has
        // none, so it is the divider role only.
        outline = textDim,
        outlineVariant = noise,
        scrim = Color.Black,
        surfaceBright = card,
        surfaceContainer = fill,
        surfaceContainerHigh = fill,
        surfaceContainerHighest = card,
        surfaceContainerLow = fill,
        surfaceContainerLowest = bg,
        surfaceDim = bg,
    )
}

internal val DarkColorScheme = ctColorScheme(dark = true)
internal val LightColorScheme = ctColorScheme(dark = false)

/**
 * Material's corner roles on the CT scale. Material components read these: menus `extraSmall`,
 * chips and snackbars `small`, cards `medium`, sheets and dialogs `extraLarge`. The library's own
 * 28 on a dialog was rounder than anything else in the app.
 */
internal val CTShapes = Shapes(
    extraSmall = RoundedCornerShape(CornerRadius.badge),
    small = RoundedCornerShape(CornerRadius.small),
    medium = RoundedCornerShape(CornerRadius.medium),
    large = RoundedCornerShape(CornerRadius.large),
    extraLarge = RoundedCornerShape(CornerRadius.extraLarge),
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
            shapes = CTShapes,
            content = content
        )
    }
}