package com.construct.messenger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.ctBold
import com.construct.messenger.ui.theme.ctRegular

/**
 * Visual role that controls the fill, border, and foreground color of a row.
 *
 * **Canon:** iOS `ConstructRowComponents.swift` → `enum ConstructRowRole`.
 */
enum class ConstructRowRole {
    /** Primary action — electric-blue tint, strong accent border. */
    PRIMARY,

    /** Accent action — lighter electric-blue tint. */
    ACCENT,

    /** Standard secondary action — neutral dark background. */
    SECONDARY,

    /** Destructive action — red tint. */
    DESTRUCTIVE,

    /** Coming-soon / unavailable — dimmed, shows "soon" badge. */
    DISABLED,
}

/**
 * A full-width tappable row styled according to [role].
 *
 * **Canon:** iOS `ConstructRowComponents.swift` → `struct ConstructActionRow`.
 * - 16dp horizontal / 13dp vertical padding.
 * - `ctBold(16)` title.
 * - 8dp rounded rectangle with tinted fill and border.
 * - [badge] rendered as a small pill on the trailing side.
 * - [isLoading] replaces the badge with a progress indicator.
 */
@Composable
fun ConstructActionRow(
    icon: ImageVector,
    title: String,
    role: ConstructRowRole,
    badge: String? = null,
    isLoading: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(8.dp)
    val isDisabled = role == ConstructRowRole.DISABLED || isLoading

    Row(
        modifier = modifier
            .clip(shape)
            .clickable(enabled = !isDisabled, onClick = onClick)
            .background(role.fill())
            .border(width = 1.dp, color = role.border(), shape = shape)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = role.foreground(),
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = title,
            style = ctBold(16),
            color = role.foreground(),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        when {
            isLoading -> {
                CircularProgressIndicator(
                    color = role.foreground(),
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(16.dp),
                )
            }

            role == ConstructRowRole.DISABLED -> {
                ActionRowBadge(text = badge ?: "soon")
            }

            badge != null -> {
                ActionRowBadge(text = badge)
            }
        }
    }
}

@Composable
private fun ActionRowBadge(text: String) {
    Text(
        text = text,
        style = ctRegular(10),
        color = CTColor.textDim,
        modifier = Modifier
            .background(CTColor.bgMsg)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun ConstructRowRole.fill(): Color = when (this) {
    ConstructRowRole.PRIMARY -> CTColor.accent.copy(alpha = 0.12f)
    ConstructRowRole.ACCENT -> CTColor.accent.copy(alpha = 0.08f)
    ConstructRowRole.DESTRUCTIVE -> CTColor.danger.copy(alpha = 0.10f)
    ConstructRowRole.SECONDARY, ConstructRowRole.DISABLED -> CTColor.bgMsg
}

@Composable
private fun ConstructRowRole.border(): Color = when (this) {
    ConstructRowRole.PRIMARY -> CTColor.accent.copy(alpha = 0.35f)
    ConstructRowRole.ACCENT -> CTColor.accent.copy(alpha = 0.25f)
    ConstructRowRole.DESTRUCTIVE -> CTColor.danger.copy(alpha = 0.30f)
    ConstructRowRole.SECONDARY, ConstructRowRole.DISABLED -> CTColor.noise
}

@Composable
private fun ConstructRowRole.foreground(): Color = when (this) {
    ConstructRowRole.PRIMARY, ConstructRowRole.ACCENT -> CTColor.accent
    ConstructRowRole.DESTRUCTIVE -> CTColor.danger
    ConstructRowRole.DISABLED -> CTColor.textDim
    ConstructRowRole.SECONDARY -> CTColor.text
}

@Preview(backgroundColor = 0xFF090909, showBackground = true, widthDp = 360)
@Composable
private fun ConstructActionRowPreview() {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(16.dp),
    ) {
        ConstructActionRow(
            icon = Icons.Default.Bolt,
            title = "Primary",
            role = ConstructRowRole.PRIMARY,
            onClick = {},
        )
        ConstructActionRow(
            icon = Icons.Default.AutoAwesome,
            title = "Accent",
            role = ConstructRowRole.ACCENT,
            onClick = {},
        )
        ConstructActionRow(
            icon = Icons.Default.Settings,
            title = "Secondary",
            role = ConstructRowRole.SECONDARY,
            onClick = {},
        )
        ConstructActionRow(
            icon = Icons.Default.Delete,
            title = "Destructive",
            role = ConstructRowRole.DESTRUCTIVE,
            onClick = {},
        )
        ConstructActionRow(
            icon = Icons.Default.Refresh,
            title = "Disabled",
            role = ConstructRowRole.DISABLED,
            onClick = {},
        )
        ConstructActionRow(
            icon = Icons.Default.Refresh,
            title = "Loading",
            role = ConstructRowRole.PRIMARY,
            isLoading = true,
            onClick = {},
        )
    }
}
