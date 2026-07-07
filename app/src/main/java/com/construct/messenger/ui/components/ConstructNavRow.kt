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
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Info
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
import com.construct.messenger.ui.theme.ctRegular

/**
 * Navigation row with a leading icon, title and trailing chevron.
 *
 * **Canon:** iOS `ConstructTheme.swift` → `struct ConstructNavRow`.
 * - Fixed 28dp leading column for the icon, aligned center.
 * - `ctRegular(13)` title in `CTColor.text`.
 * - Trailing `Icons.Default.ChevronRight` in `CTColor.textDim`.
 * - Padding: horizontal 12, vertical 9.
 */
@Composable
fun ConstructNavRow(
    icon: ImageVector,
    title: String,
    iconColor: Color = CTColor.accent,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(28.dp)
                .padding(end = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(15.dp),
            )
        }
        Text(
            text = title,
            style = ctRegular(13),
            color = CTColor.text,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = CTColor.textDim,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Preview(backgroundColor = 0xFF090909, showBackground = true, widthDp = 360)
@Composable
private fun ConstructNavRowPreview() {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(vertical = 8.dp),
    ) {
        CTSettingsSectionHeader(title = "Settings")
        CTSectionGroup {
            ConstructNavRow(
                icon = Icons.Default.Person,
                title = "Account",
                onClick = {},
            )
            CTSep()
            ConstructNavRow(
                icon = Icons.AutoMirrored.Filled.HelpOutline,
                title = "Help",
                iconColor = CTColor.textDim,
                onClick = {},
            )
            CTSep()
            ConstructNavRow(
                icon = Icons.Default.Info,
                title = "About",
                iconColor = CTColor.accentDim,
                onClick = {},
            )
        }
    }
}
