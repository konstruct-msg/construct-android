package com.construct.messenger.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.ctBold
import com.construct.messenger.ui.theme.ctRegular

/**
 * One row of a settings section: `[icon] label … value | status`.
 *
 * **Canon:** iOS `ConstructTheme.swift` → `struct CTSettingsRow`.
 * - Optional leading [icon] in a 28dp centered column.
 * - `ctRegular(13)` label; value is `ctBold(13)` + `accent` when [isAction].
 * - [isDestructive] paints label, icon and value in `danger`.
 * - Optional [status] renders a [CTStatusBadge] instead of the textual value.
 * - Padding: horizontal 12, vertical 9. Presentational — wrap with a clickable
 *   [modifier] for tappable rows.
 */
@Composable
fun CTSettingsRow(
    label: String,
    value: String = "",
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    labelColor: Color = CTColor.text,
    valueColor: Color = CTColor.text,
    isAction: Boolean = false,
    isDestructive: Boolean = false,
    status: CTStatus? = null,
) {
    val primaryColor = if (isDestructive) CTColor.danger else labelColor
    val resolvedValueColor = when {
        isDestructive -> CTColor.danger
        isAction -> CTColor.accent
        else -> valueColor
    }

    Row(
        modifier = modifier
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .width(28.dp)
                    .padding(end = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = primaryColor,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
        Text(
            text = label,
            style = ctRegular(13),
            color = primaryColor,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        if (status != null) {
            CTStatusBadge(status = status)
        } else {
            Text(
                text = value,
                style = if (isAction) ctBold(13) else ctRegular(13),
                color = resolvedValueColor,
                textAlign = TextAlign.End,
            )
        }
    }
}

@Preview(backgroundColor = 0xFF090909, showBackground = true, widthDp = 360)
@Composable
private fun SettingsSectionPreview() {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(vertical = 8.dp),
    ) {
        CTSettingsSectionHeader(title = "Identity")
        CTSectionGroup {
            CTSettingsRow(label = "Username", value = "silent_fox", icon = Icons.Default.Person)
            CTSep()
            CTSettingsRow(label = "Security", value = "PIN", icon = Icons.Default.Lock, isAction = true)
            CTSep()
            CTSettingsRow(label = "Network", status = CTStatus.OK)
            CTSep()
            CTSettingsRow(label = "Sign out", isDestructive = true)
        }
    }
}
