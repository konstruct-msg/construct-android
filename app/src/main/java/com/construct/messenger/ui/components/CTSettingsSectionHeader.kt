package com.construct.messenger.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTFont

/**
 * Section header rendered as `> TITLE`.
 *
 * **Canon:** iOS `ConstructTheme.swift` → `struct CTSettingsSectionHeader`.
 * - `CTFont.badge`, [color] default `accent`, title uppercased.
 * - Padding: horizontal 12, top 16 (the inter-section gap), bottom 4.
 */
@Composable
fun CTSettingsSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    color: Color = CTColor.accent,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(top = 16.dp, bottom = 4.dp),
    ) {
        Text(text = ">", style = CTFont.badge, color = color)
        Spacer(Modifier.width(6.dp))
        Text(text = title.uppercase(), style = CTFont.badge, color = color)
    }
}
