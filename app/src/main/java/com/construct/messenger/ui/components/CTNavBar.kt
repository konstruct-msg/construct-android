package com.construct.messenger.ui.components

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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
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
 * - Trailing: an icon ([trailingIcon]) and/or an ASCII symbol ([trailingSymbol]);
 *   an optional secondary icon ([trailingSecondaryIcon]) renders to its left
 *   (typically a muted cancel next to a primary confirm).
 * - Fixed height 44dp, horizontal padding 12dp, 0.5dp bottom border in `noise`.
 *
 * SF Symbols → interactive controls map to Material icons; the trailing ASCII
 * symbol (`CTSymbol.*`) is for decorative/structural actions.
 */
@Composable
fun CTNavBar(
    title: String,
    modifier: Modifier = Modifier,
    showBack: Boolean = false,
    isModal: Boolean = false,
    trailingIcon: ImageVector? = null,
    trailingSymbol: String? = null,
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
            .bottomHairline()
            .padding(horizontal = CTLayout.edgePad),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showBack) {
            Icon(
                imageVector = if (isModal) Icons.Default.Close
                              else Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
                tint = CTColor.accent,
                modifier = Modifier
                    .size(CTLayout.navIconSizeLg)
                    .clickable(onClick = onBack),
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
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = trailingSecondaryColor,
                modifier = Modifier
                    .size(18.dp)
                    .clickable(onClick = onTrailingSecondaryAction),
            )
            Spacer(Modifier.width(10.dp))
        }

        when {
            trailingIcon != null -> Icon(
                imageVector = trailingIcon,
                contentDescription = null,
                tint = trailingColor,
                modifier = Modifier
                    .size(18.dp)
                    .clickable(onClick = onTrailingAction),
            )
            trailingSymbol != null -> Text(
                text = trailingSymbol,
                style = ctRegular(13),
                color = trailingColor,
                modifier = Modifier.clickable(onClick = onTrailingAction),
            )
        }
    }
}

/** 0.5dp separator line on the bottom edge — iOS `ctBorderBottom()`. */
private fun Modifier.bottomHairline(): Modifier = drawBehind {
    val stroke = 0.5.dp.toPx()
    drawLine(
        color = CTColor.noise,
        start = Offset(0f, size.height - stroke / 2f),
        end = Offset(size.width, size.height - stroke / 2f),
        strokeWidth = stroke,
    )
}

@Preview(backgroundColor = 0xFF090909, showBackground = true)
@Composable
private fun CTNavBarBackPreview() {
    CTNavBar(
        title = "Settings",
        showBack = true,
        trailingSymbol = "[+]",
    )
}

@Preview(backgroundColor = 0xFF090909, showBackground = true)
@Composable
private fun CTNavBarTitleOnlyPreview() {
    CTNavBar(title = "Stream")
}
