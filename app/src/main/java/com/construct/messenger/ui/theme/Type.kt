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

/**
 * The chrome's type scale. **Canon:** iOS `CTFont` (`ConstructTheme.swift`).
 *
 * A screen picks a role, not a size: a change to "what body text is" happens here once. The roles
 * are the (weight, size) pairs that cover most of the chrome on both platforms; a size not in the
 * list is [ui] directly. Sizes are sp, so they follow the system font scale — Android's Dynamic Type.
 *
 * The fixed-size helpers this replaced (`ctRegular(13)`, `ctBold(15)` …) let every screen choose its
 * own size, and by 2026-10-06 one kind of element had 2–5 sizes across screens. They are gone, as the
 * iOS pre-split names are.
 */
object CTFont {
    /** Chrome: nav bars, labels, section headers, buttons — anything read to operate the app. */
    fun ui(size: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
        fontFamily = CTFontFamily,
        fontSize = size.sp,
        fontWeight = weight,
    )

    /**
     * Technical content: hex ids, fingerprints, safety numbers, device ids, logs. Monospace here is
     * a decision about what the content *is*, so it stays monospace whatever the chrome is set in.
     */
    fun mono(size: Int, weight: FontWeight = FontWeight.Normal) = ui(size, weight)

    /** Screen and sheet titles. */
    val title = ui(18, FontWeight.Bold)
    /** Section titles, the root title of a tab, emphasised labels. */
    val headline = ui(14, FontWeight.Bold)
    /** Row labels and the ordinary text of the chrome. */
    val body = ui(13)
    /** Body with emphasis: buttons, the name in the chat list. */
    val bodyEmphasis = ui(13, FontWeight.Bold)
    /** Row values, secondary lines, previews. */
    val secondary = ui(12)
    /** Captions, timestamps, footers. */
    val caption = ui(11)
    /** The smallest text the chrome sets: the bubble's time, call button labels. */
    val micro = ui(10)
    /** Section headers, counters, chips. */
    val badge = ui(11, FontWeight.Bold)

    /**
     * Message text — bubbles and the composer that fills them — in the reader's face and size.
     * The only font in the app the reader chooses; everything else is chrome.
     */
    @Composable
    @ReadOnlyComposable
    fun message(size: Int): TextStyle {
        val chat = LocalChatText.current
        return TextStyle(
            fontFamily = if (chat.monospace) CTFontFamily else FontFamily.Default,
            fontSize = (size * chat.multiplier).sp,
            fontWeight = FontWeight.Normal,
        )
    }
}

/** What message text is set in: [monospace] or the platform face, scaled by [multiplier]. */
data class ChatText(val monospace: Boolean = false, val multiplier: Float = 1f)

val Typography = Typography(
    bodyLarge = CTFont.ui(16),
    bodyMedium = CTFont.ui(14),
    bodySmall = CTFont.secondary,
    titleLarge = CTFont.ui(22, FontWeight.Bold),
    titleMedium = CTFont.title,
    titleSmall = CTFont.headline,
    labelLarge = CTFont.ui(14),
    labelMedium = CTFont.secondary,
    labelSmall = CTFont.micro,
)
