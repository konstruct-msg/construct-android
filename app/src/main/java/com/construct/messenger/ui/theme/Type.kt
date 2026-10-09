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
 * JetBrains Mono, bundled — the chrome's face on both platforms (the same four files as iOS
 * `Fonts/`, OFL 1.1, licence in `assets/licenses/`). `FontFamily.Monospace` this replaced was
 * whatever the phone ships (Droid Sans Mono on most).
 */
val CTFontFamily = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
    Font(R.font.jetbrains_mono_semibold, FontWeight.SemiBold),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
)

/**
 * The chrome's type scale, for text outside Material components; Material's `Typography` below
 * is built from these roles. The scale came from iOS `CTFont`; it is ours now, and the designer's
 * file may change it here.
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

/**
 * Material's type roles, all in JetBrains Mono. Step 1 of the Material 3 move: the roles a screen
 * already reads keep today's values (a `Text` without a style is `bodyLarge`), and the ones nothing
 * set — display and headline — take Material's sizes in our face instead of Roboto. A role is
 * matched to its CT counterpart when the component that reads it is swapped (step 2), where the
 * screenshot diff shows it: the dialog's (`headlineSmall`, `bodyMedium`, `labelLarge`) and the text field's (`bodyLarge`)
 * so far.
 */
private val materialDefaults = Typography()

val Typography = Typography(
    displayLarge = materialDefaults.displayLarge.copy(fontFamily = CTFontFamily),
    displayMedium = materialDefaults.displayMedium.copy(fontFamily = CTFontFamily),
    displaySmall = materialDefaults.displaySmall.copy(fontFamily = CTFontFamily),
    headlineLarge = materialDefaults.headlineLarge.copy(fontFamily = CTFontFamily),
    headlineMedium = materialDefaults.headlineMedium.copy(fontFamily = CTFontFamily),
    // A dialog's title (AlertDialog). 15 bold, as CT dialogs have always set it by hand.
    headlineSmall = CTFont.ui(15, FontWeight.Bold),
    // A text field's input and placeholder (OutlinedTextField), and any Text given no style.
    // 14, as CT fields were: at 16 the onboarding alias placeholder no longer fits a phone.
    bodyLarge = CTFont.ui(14),
    // A dialog's text, a list item's supporting line.
    bodyMedium = CTFont.body,
    bodySmall = CTFont.secondary,
    titleLarge = CTFont.ui(22, FontWeight.Bold),
    titleMedium = CTFont.title,
    titleSmall = CTFont.headline,
    // Buttons: TextButton, Button, a dialog's actions.
    labelLarge = CTFont.bodyEmphasis,
    labelMedium = CTFont.secondary,
    labelSmall = CTFont.micro,
)
