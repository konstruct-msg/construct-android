package com.construct.messenger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CornerRadius
import com.construct.messenger.ui.theme.HairlineBorder

/**
 * Rounded card container for a settings section (the [CTSettingsRow] pattern).
 *
 * **Canon:** iOS `ConstructTheme.swift` → `struct CTSectionGroup`.
 * - `outMsgBg` background, corner radius 8, 0.5dp `noise` border, 12dp horizontal padding.
 * - Wrap the rows of one section (NOT the [CTSettingsSectionHeader]); the header's
 *   top padding provides the gap between sections.
 */
@Composable
fun CTSectionGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(CornerRadius.small)
    Column(
        modifier = modifier
            .padding(horizontal = 12.dp)
            .clip(shape)
            .background(CTColor.outMsgBg)
            .border(HairlineBorder, CTColor.noise, shape),
        content = content,
    )
}
