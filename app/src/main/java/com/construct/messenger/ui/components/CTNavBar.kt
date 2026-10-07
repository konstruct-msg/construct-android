package com.construct.messenger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTFont
import com.construct.messenger.ui.theme.CTIcon
import com.construct.messenger.ui.theme.CTLayout

/**
 * Navigation bar — reusable header.
 *
 * **Canon:** iOS `ConstructTheme.swift` → `struct CTNavBar` for a pushed screen: the title as
 *   given, `CTFont.ui(17, FontWeight.SemiBold)`, no letter-spacing (iOS dropped the uppercase + tracking on all its
 *   screens — it read as a machine label, not a screen's name); back is a filled accent circle
 *   with a chevron (`chevron.backward.circle.fill`, 22pt). Bottom border.
 * - A tab's root (no back, not modal) is iOS's root header instead: uppercase, `CTFont.headline`,
 *   tracking 4, no border (iOS `SettingsView` header).
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
    val isRoot = !showBack && !isModal
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(CTLayout.navBarHeight)
            .background(CTColor.bg)
            .then(if (isRoot) Modifier else Modifier.ctBorderBottom())
            .padding(horizontal = CTLayout.edgePad),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showBack) {
            NavBarBackButton(isModal = isModal, onClick = onBack)
            Spacer(Modifier.width(14.dp))
        }

        // The title takes the row up to the trailing icons. A title weighted beside a weighted
        // spacer got half the row and cut "Use existing identity" to "Use existing id…".
        Text(
            text = if (isRoot) title.uppercase() else title,
            style = if (isRoot) CTFont.headline else CTFont.ui(17, FontWeight.SemiBold),
            color = CTColor.text,
            letterSpacing = if (isRoot) 4.sp else TextUnit.Unspecified,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

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

/** iOS `chevron.backward.circle.fill` at 22pt: an accent disc, the chevron cut out of it. */
@Composable
private fun NavBarBackButton(isModal: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .layout { measurable, _ ->
                val target = CTLayout.navBarHeight.roundToPx()
                val glyph = CTLayout.navIconSizeLg.roundToPx()
                val placeable = measurable.measure(Constraints.fixed(target, target))
                layout(glyph, glyph) { placeable.place((glyph - target) / 2, (glyph - target) / 2) }
            }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(CTLayout.navIconSizeLg)
                .background(CTColor.accent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (isModal) Icons.Default.Close else Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = null,
                tint = CTColor.bg,
                modifier = Modifier.size(CTIcon.nav),
            )
        }
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
