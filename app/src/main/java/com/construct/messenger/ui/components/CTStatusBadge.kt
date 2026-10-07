package com.construct.messenger.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTSpace

/**
 * Canonical status indicator.
 *
 * **Canon:** iOS `ConstructTheme.swift` → `enum CTStatus` + `struct CTStatusBadge`.
 * Never render status as an ASCII token such as "[ok]" / "[err]". Use Material
 * icons + semantic color instead.
 */
enum class CTStatus {
    OK, ERROR, WARNING, ON, OFF, BUSY, UNKNOWN;

    val icon: ImageVector
        get() = when (this) {
            OK, ON -> Icons.Filled.CheckCircle
            ERROR -> Icons.Filled.Error
            WARNING -> Icons.Filled.Warning
            OFF -> Icons.Outlined.Circle
            BUSY -> Icons.Filled.Sync
            UNKNOWN -> Icons.AutoMirrored.Filled.HelpOutline
        }

    val color: Color
        get() = when (this) {
            OK -> CTColor.accent
            ON -> CTColor.accentDim
            ERROR -> CTColor.danger
            WARNING -> CTColor.warning
            OFF, BUSY, UNKNOWN -> CTColor.textDim
        }
}

/**
 * Renders a [CTStatus] as a tinted Material icon.
 *
 * @param status The status to display.
 * @param size Icon size in dp; defaults to 14dp to match row text height.
 * @param modifier Optional modifier for the icon.
 * @param tint The status's own colour unless a screen says otherwise (the network status: green).
 */
@Composable
fun CTStatusBadge(
    status: CTStatus,
    size: Dp = 14.dp,
    modifier: Modifier = Modifier,
    tint: Color = status.color,
) {
    Icon(
        imageVector = status.icon,
        contentDescription = status.name,
        tint = tint,
        modifier = modifier.size(size),
    )
}

@Preview(backgroundColor = 0xFF090909, showBackground = true)
@Composable
private fun CTStatusBadgePreview() {
    Row(
        modifier = Modifier.padding(CTSpace.m),
        horizontalArrangement = Arrangement.spacedBy(CTSpace.s)
    ) {
        CTStatusBadge(CTStatus.OK)
        CTStatusBadge(CTStatus.ON)
        CTStatusBadge(CTStatus.ERROR)
        CTStatusBadge(CTStatus.WARNING)
        CTStatusBadge(CTStatus.OFF)
        CTStatusBadge(CTStatus.BUSY)
        CTStatusBadge(CTStatus.UNKNOWN)
    }
}
