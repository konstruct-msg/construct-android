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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import com.construct.messenger.ui.theme.CTFont
import com.construct.messenger.ui.theme.CTIcon
import com.construct.messenger.ui.theme.CTSpace

/**
 * One row of a settings section: `[icon] label … value | status`.
 *
 * **Canon:** iOS `ConstructTheme.swift` → `struct CTSettingsRow`.
 * - Optional leading [icon] in a 28dp centered column.
 * - `CTFont.body` label; value is `CTFont.bodyEmphasis` + `accent` when [isAction].
 * - [isDestructive] paints label, icon and value in `danger`.
 * - Optional [status] renders a [CTStatusBadge] after the value.
 * - [disclosure] adds a trailing chevron: the row opens another screen.
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
    disclosure: Boolean = false,
) {
    val primaryColor = if (isDestructive) CTColor.danger else labelColor
    val resolvedValueColor = when {
        isDestructive -> CTColor.danger
        isAction -> CTColor.accent
        else -> valueColor
    }

    Row(
        modifier = modifier
            .padding(horizontal = CTSpace.m, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .width(28.dp)
                    .padding(end = CTSpace.xs),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = primaryColor,
                    modifier = Modifier.size(CTIcon.row),
                )
            }
        }
        Text(
            text = label,
            style = CTFont.body,
            color = primaryColor,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(CTSpace.s))
        if (value.isNotEmpty()) {
            Text(
                text = value,
                style = if (isAction) CTFont.bodyEmphasis else CTFont.body,
                color = resolvedValueColor,
                textAlign = TextAlign.End,
                maxLines = 1,
            )
        }
        if (status != null) {
            CTStatusBadge(
                status = status,
                modifier = Modifier.padding(start = if (value.isEmpty()) 0.dp else 6.dp),
            )
        }
        if (disclosure) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = if (isDestructive) CTColor.danger else CTColor.textDim,
                modifier = Modifier
                    .padding(start = if (value.isEmpty() && status == null) 0.dp else 6.dp)
                    .size(CTIcon.row),
            )
        }
    }
}

@Preview(backgroundColor = 0xFF090909, showBackground = true, widthDp = 360)
@Composable
private fun SettingsSectionPreview() {
    Column(
        verticalArrangement = Arrangement.spacedBy(CTSpace.xs),
        modifier = Modifier.padding(vertical = CTSpace.s),
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
