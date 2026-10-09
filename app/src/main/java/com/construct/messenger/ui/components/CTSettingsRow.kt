package com.construct.messenger.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.construct.messenger.ui.theme.CTSpace

/**
 * One row of a settings section: `[icon] label … value | status ›`.
 *
 * Material `ListItem` (`docs/MATERIAL3_MIGRATION.md`, step 2): height (56 dp for one line), padding,
 * type and colours are Material's — the owner chose Material density on 2026-10-08. The container
 * is transparent because the rows still sit in a [CTSectionGroup] card. What the row adds is the
 * settings vocabulary: a value on the trailing side, a [CTStatusBadge], a disclosure chevron, and
 * [isDestructive] / [isAction] colouring.
 *
 * Presentational — wrap with a clickable [modifier] for tappable rows; `ListItem` draws the
 * ripple over the whole row.
 *
 * @param labelColor Null is the theme's `onSurface`; a colour given tints the icon too.
 * @param valueColor Null is the theme's `onSurfaceVariant`.
 */
@Composable
fun CTSettingsRow(
    label: String,
    value: String = "",
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    labelColor: Color? = null,
    valueColor: Color? = null,
    isAction: Boolean = false,
    isDestructive: Boolean = false,
    status: CTStatus? = null,
    disclosure: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val headline = when {
        isDestructive -> scheme.error
        else -> labelColor ?: scheme.onSurface
    }
    val leading = if (isDestructive || labelColor != null) headline else scheme.onSurfaceVariant
    val trailing = when {
        isDestructive -> scheme.error
        isAction -> scheme.primary
        else -> valueColor ?: scheme.onSurfaceVariant
    }

    ListItem(
        headlineContent = { Text(label) },
        modifier = modifier,
        leadingContent = icon?.let { { Icon(it, contentDescription = null) } },
        trailingContent = if (value.isEmpty() && status == null && !disclosure) null else {
            {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(CTSpace.xs),
                ) {
                    if (value.isNotEmpty()) {
                        Text(
                            text = value,
                            style = if (isAction) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium,
                            color = trailing,
                            maxLines = 1,
                        )
                    }
                    if (status != null) CTStatusBadge(status = status)
                    if (disclosure) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                    }
                }
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
            headlineColor = headline,
            leadingIconColor = leading,
            trailingIconColor = if (isDestructive) scheme.error else scheme.onSurfaceVariant,
        ),
    )
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
