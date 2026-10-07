package com.construct.messenger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTIcon
import com.construct.messenger.ui.theme.CTSpace

/**
 * One tab. iOS renders the icon only (the ASCII `symbol` is not shown in the bar),
 * toggling to a `.fill` variant when selected — mirrored here with [selectedIcon].
 */
data class CTTabItem(
    val icon: ImageVector,
    val selectedIcon: ImageVector = icon,
    val contentDescription: String? = null,
)

/** Default 3-tab layout: chats / synaps / settings (iOS `CTTabBar.defaultItems`). */
val ctDefaultTabItems: List<CTTabItem> = listOf(
    CTTabItem(Icons.AutoMirrored.Outlined.Chat, Icons.AutoMirrored.Filled.Chat, "Chats"),
    CTTabItem(Icons.Outlined.Groups, Icons.Filled.Groups, "Synaps"),
    CTTabItem(Icons.Outlined.Settings, Icons.Filled.Settings, "Settings"),
)

/**
 * Bottom tab bar.
 *
 * **Canon:** iOS `ConstructTheme.swift` → `struct CTTabBar`.
 * - Evenly distributed icons (size 20dp), `accent` when selected else `textDim`.
 * - 10dp vertical padding, 0.5dp `noise` top border.
 */
@Composable
fun CTTabBar(
    selectedTab: Int,
    items: List<CTTabItem>,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(CTColor.bg)
            .ctBorderTop()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEachIndexed { index, item ->
            val selected = index == selectedTab
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onTabSelected(index) }
                    .padding(vertical = CTSpace.xs),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (selected) item.selectedIcon else item.icon,
                    contentDescription = item.contentDescription,
                    tint = if (selected) CTColor.accent else CTColor.textDim,
                    modifier = Modifier.size(CTIcon.nav),
                )
            }
        }
    }
}

@Preview(backgroundColor = 0xFF090909, showBackground = true, widthDp = 360)
@Composable
private fun CTTabBarPreview() {
    var selected by remember { mutableIntStateOf(0) }
    CTTabBar(
        selectedTab = selected,
        items = ctDefaultTabItems,
        onTabSelected = { selected = it },
    )
}
