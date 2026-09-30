package com.construct.messenger.ui.theme

import androidx.compose.material3.Typography
import com.construct.messenger.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * JetBrains Mono, bundled — iOS `ConstructFont.mono` sets the chrome in it (`Fonts/` in
 * construct-messenger; the same four files here, OFL 1.1, licence in `assets/licenses/`).
 * `FontFamily.Monospace` this replaces was whatever the phone ships (Droid Sans Mono on most),
 * so every screen read differently from iOS.
 */
val CTFontFamily = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
    Font(R.font.jetbrains_mono_semibold, FontWeight.SemiBold),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
)
val CTFontRegular = CTFontFamily
val CTFontBold = CTFontFamily

fun ctRegular(size: Int) = TextStyle(
    fontFamily = CTFontRegular,
    fontSize = size.sp,
    fontWeight = FontWeight.Normal
)

/** iOS `CTFont.ui(size, weight: .medium)`. */
fun ctMedium(size: Int) = TextStyle(
    fontFamily = CTFontFamily,
    fontSize = size.sp,
    fontWeight = FontWeight.Medium
)

/** iOS `CTFont.ui(size, weight: .semibold)` — the nav bar title. */
fun ctSemiBold(size: Int) = TextStyle(
    fontFamily = CTFontFamily,
    fontSize = size.sp,
    fontWeight = FontWeight.SemiBold
)

fun ctBold(size: Int) = TextStyle(
    fontFamily = CTFontBold,
    fontSize = size.sp,
    fontWeight = FontWeight.Bold
)

/** What message text is set in: [monospace] or the platform face, scaled by [multiplier]. */
data class ChatText(val monospace: Boolean = false, val multiplier: Float = 1f)

/**
 * Message text — bubbles and the composer that fills them — in the reader's face and size.
 *
 * **Canon:** iOS `CTFont.message`. Everything else is chrome and stays [ctRegular].
 */
@Composable
@ReadOnlyComposable
fun ctMessage(size: Int): TextStyle {
    val chat = LocalChatText.current
    return TextStyle(
        fontFamily = if (chat.monospace) CTFontRegular else FontFamily.Default,
        fontSize = (size * chat.multiplier).sp,
        fontWeight = FontWeight.Normal,
    )
}

val Typography = Typography(
    bodyLarge = ctRegular(16),
    bodyMedium = ctRegular(14),
    bodySmall = ctRegular(12),
    titleLarge = ctBold(22),
    titleMedium = ctBold(18),
    titleSmall = ctBold(14),
    labelLarge = ctRegular(14),
    labelMedium = ctRegular(12),
    labelSmall = ctRegular(10)
)