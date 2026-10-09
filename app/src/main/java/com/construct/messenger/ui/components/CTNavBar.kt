package com.construct.messenger.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp
import com.construct.messenger.R
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTFont
import com.construct.messenger.ui.theme.HairlineBorder

/**
 * A screen's top bar.
 *
 * Material `TopAppBar` (`docs/MATERIAL3_MIGRATION.md`, step 2): height, title type, the back
 * arrow and the 48 dp touch targets come from Material. The signature is the one the screens
 * already call, so none of them changed.
 *
 * - [showBack]: Material's back arrow, or a close cross when [isModal].
 * - Trailing: [trailingIcon]; [trailingSecondaryIcon] sits to its left (typically a muted cancel
 *   next to a primary confirm).
 * - A tab's root (no back, not modal) keeps our header — uppercase, tracked — and no border; a
 *   pushed screen gets our hairline divider under the bar.
 * - No window insets: every screen pads itself clear of the status bar, and inside the tab
 *   Scaffold the bars are already consumed.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    Column(modifier) {
        TopAppBar(
            windowInsets = WindowInsets(0),
            title = {
                if (isRoot) {
                    Text(
                        text = title.uppercase(),
                        style = CTFont.headline,
                        letterSpacing = 4.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Text(text = title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            },
            navigationIcon = {
                if (showBack) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = if (isModal) Icons.Default.Close else Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(if (isModal) R.string.close else R.string.back),
                        )
                    }
                }
            },
            actions = {
                trailingSecondaryIcon?.let { icon ->
                    IconButton(onClick = onTrailingSecondaryAction) {
                        Icon(imageVector = icon, contentDescription = null, tint = trailingSecondaryColor)
                    }
                }
                trailingIcon?.let { icon ->
                    IconButton(onClick = onTrailingAction) {
                        Icon(imageVector = icon, contentDescription = null, tint = trailingColor)
                    }
                }
            },
        )
        if (!isRoot) HorizontalDivider(thickness = HairlineBorder)
    }
}

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
