package com.construct.messenger.ui.components

import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTLayout
import com.construct.messenger.ui.theme.ctBold
import com.construct.messenger.ui.theme.ctRegular

/**
 * Navigation bar — reusable header.
 *
 * **Canon:** iOS `ConstructTheme.swift` → `struct CTNavBar`.
 * - Title is `.uppercase()` + `ctBold(14)` + `letterSpacing(4)`.
 * - Leading: optional back (chevron) / close (when [isModal]).
 * - Trailing: an icon ([trailingIcon]); an optional secondary icon
 *   ([trailingSecondaryIcon]) renders to its left (typically a muted cancel next
 *   to a primary confirm).
 * - Fixed height 44dp, horizontal padding 12dp, 0.5dp bottom border in `noise`.
 *
 * SF Symbols → interactive controls map to Material icons. ASCII glyphs are
 * never used for functional controls (see `CTSymbol` for decorative chrome only).
 */
@Composable
fun CTNavBar(
    title: String,
    modifier: Modifier = Modifier,
    showBack: Boolean = false,
    isModal: Boolean = false,
    trailingIcon: ImageVector? = null,
    trailingColor: Color = CTColor.accent,
    trailingSecondaryIcon: ImageVector? = null,
    trailingSecondaryColor: Color = CTColor.textDim,
    onBack: () -> Unit = {},
    onTrailingAction: () -> Unit = {},
    onTrailingSecondaryAction: () -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(CTLayout.navBarHeight)
            .background(CTColor.bg)
            .ctBorderBottom()
            .padding(horizontal = CTLayout.edgePad),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showBack) {
            NavBarIcon(
                icon = if (isModal) Icons.Default.Close else Icons.AutoMirrored.Filled.ArrowBack,
                tint = CTColor.accent,
                iconSize = CTLayout.navIconSizeLg,
                onClick = onBack,
            )
            Spacer(Modifier.width(10.dp))
        }

        Text(
            text = title.uppercase(),
            style = ctBold(14),
            color = CTColor.text,
            letterSpacing = 4.sp,
        )

        Spacer(Modifier.weight(1f))

        trailingSecondaryIcon?.let { icon ->
            NavBarIcon(
                icon = icon,
                tint = trailingSecondaryColor,
                iconSize = TRAILING_ICON_SIZE,
                onClick = onTrailingSecondaryAction,
            )
            Spacer(Modifier.width(10.dp))
        }

        trailingIcon?.let { icon ->
            NavBarIcon(
                icon = icon,
                tint = trailingColor,
                iconSize = TRAILING_ICON_SIZE,
                onClick = onTrailingAction,
            )
        }
    }
}

/**
 * A nav-bar glyph with a touch target the height of the bar (44dp — iOS HIG's minimum, and the
 * design canon). The glyph alone was the target: 18dp, about 50px, and a tap one pixel off the
 * "my QR" icon did nothing on a device (2026-09-25).
 *
 * The target overhangs: the row is told the glyph's size, so spacing and alignment are exactly
 * what they were, and the 44dp box is centred on the glyph.
 */
@Composable
private fun NavBarIcon(
    icon: ImageVector,
    tint: Color,
    iconSize: Dp,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .layout { measurable, _ ->
                val target = CTLayout.navBarHeight.roundToPx()
                val glyph = iconSize.roundToPx()
                val placeable = measurable.measure(Constraints.fixed(target, target))
                layout(glyph, glyph) { placeable.place((glyph - target) / 2, (glyph - target) / 2) }
            }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(iconSize))
    }
}

private val TRAILING_ICON_SIZE = 18.dp

@Preview(backgroundColor = 0xFF090909, showBackground = true)
@Composable
private fun CTNavBarBackPreview() {
    CTNavBar(
        title = "Settings",
        showBack = true,
        trailingIcon = Icons.Default.Add,
    )
}

@Preview(backgroundColor = 0xFF090909, showBackground = true)
@Composable
private fun CTNavBarTitleOnlyPreview() {
    CTNavBar(title = "Stream")
}
