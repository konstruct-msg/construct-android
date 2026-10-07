package com.construct.messenger.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTFont
import com.construct.messenger.ui.theme.CTIcon
import com.construct.messenger.ui.theme.CTSpace

/**
 * Button row with a leading icon, title and optional trailing chevron.
 *
 * **Canon:** iOS `ConstructTheme.swift` → `struct ConstructButtonRow`.
 * - Fixed 28dp leading column for the icon, aligned center.
 * - `CTFont.body` title in `CTColor.text`.
 * - Optional trailing `Icons.Default.ChevronRight` when [showChevron] is true.
 * - Padding: horizontal 12, vertical 9.
 */
@Composable
fun ConstructButtonRow(
    icon: ImageVector,
    title: String,
    iconColor: Color = CTColor.accent,
    showChevron: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(horizontal = CTSpace.m, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(28.dp)
                .padding(end = CTSpace.xs),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(CTIcon.row),
            )
        }
        Text(
            text = title,
            style = CTFont.body,
            color = CTColor.text,
            modifier = Modifier.weight(1f),
        )
        if (showChevron) {
            Spacer(Modifier.width(CTSpace.s))
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = CTColor.textDim,
                modifier = Modifier.size(CTIcon.row),
            )
        }
    }
}

@Preview(backgroundColor = 0xFF090909, showBackground = true, widthDp = 360)
@Composable
private fun ConstructButtonRowPreview() {
    Column(
        verticalArrangement = Arrangement.spacedBy(CTSpace.xs),
        modifier = Modifier.padding(vertical = CTSpace.s),
    ) {
        CTSettingsSectionHeader(title = "Actions")
        CTSectionGroup {
            ConstructButtonRow(
                icon = Icons.Default.Add,
                title = "Add account",
                onClick = {},
            )
            CTSep()
            ConstructButtonRow(
                icon = Icons.Default.Person,
                title = "Invite friend",
                iconColor = CTColor.accentDim,
                showChevron = true,
                onClick = {},
            )
            CTSep()
            ConstructButtonRow(
                icon = Icons.AutoMirrored.Filled.HelpOutline,
                title = "Help center",
                iconColor = CTColor.textDim,
                onClick = {},
            )
        }
    }
}
