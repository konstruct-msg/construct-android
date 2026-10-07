package com.construct.messenger.ui.screens.main

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.construct.messenger.R
import com.construct.messenger.ui.components.TabIcons
import com.construct.messenger.ui.screens.calls.CallsScreen
import com.construct.messenger.ui.screens.chats.ChatsListScreen
import com.construct.messenger.ui.screens.settings.SettingsNavigation
import com.construct.messenger.ui.screens.settings.SettingsRoute
import com.construct.messenger.ui.screens.synaps.SynapsScreen
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTIcon
import com.construct.messenger.viewmodel.PendingChatViewModel

/**
 * Root tab container: Chats | Synaps | Calls | Settings.
 *
 * **Canon:** iOS `MainTabView` — the system `TabView`, icons only: outline when idle, filled
 * and accent when selected, no pill behind it, no bar background. Material's `NavigationBar`
 * is 80dp tall with a selection pill, which is what made the two apps look unrelated.
 */
@Composable
fun MainTabView(
    onNavigateToChat: (String) -> Unit,
    onOpenContact: (String) -> Unit = {},
    onScanQr: () -> Unit = {},
    onShowMyQr: () -> Unit = {},
    settingsNavigation: SettingsNavigation = SettingsNavigation(),
    pendingChatViewModel: PendingChatViewModel = hiltViewModel(),
    startTab: Int = 0,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(startTab) }

    // A tapped message notification opens its chat from the nav host (`OpenTappedChat`), which
    // is there whatever screen is up.
    // A tapped invite — in a message or another app: Synaps redeems it and shows the outcome.
    val inviteWaiting by pendingChatViewModel.inviteWaiting.collectAsStateWithLifecycle()
    LaunchedEffect(inviteWaiting) {
        if (inviteWaiting != null) selectedTab = 1
    }

    val tabs = listOf(
        TabItem.Chats,
        TabItem.Synaps,
        TabItem.Calls,
        TabItem.Settings,
    )

    Scaffold(
        bottomBar = {
            TabBar(tabs = tabs, selected = selectedTab, onSelect = { selectedTab = it })
        },
        containerColor = CTColor.bg,
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                // Marks the bars the Scaffold just padded as used, so a tab screen's own
                // statusBarsPadding (needed when it is shown outside the tabs) adds nothing here.
                .consumeWindowInsets(padding)
                .background(CTColor.bg)
        ) {
            when (selectedTab) {
                0 -> ChatsListScreen(
                    onNavigateToChat = onNavigateToChat,
                    onFindPeople = { selectedTab = 1 },
                    onScanQr = onScanQr,
                    onShowMyQr = onShowMyQr,
                )
                1 -> SynapsScreen(
                    onOpenContact = onOpenContact,
                    onScanQr = onScanQr,
                )
                2 -> CallsScreen()
                3 -> SettingsRoute(navigation = settingsNavigation)
            }
        }
    }
}

@Composable
private fun TabBar(tabs: List<TabItem>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CTColor.bg)
            .navigationBarsPadding()
            .height(TAB_BAR_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEachIndexed { index, tab ->
            val isSelected = selected == index
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .selectable(
                        selected = isSelected,
                        onClick = { onSelect(index) },
                        role = Role.Tab,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = rememberRipple(bounded = false, radius = 24.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (isSelected) tab.selectedIcon else tab.icon,
                    contentDescription = stringResource(tab.labelRes),
                    tint = if (isSelected) CTColor.accent else CTColor.textDim,
                    modifier = Modifier.size(CTIcon.control),
                )
            }
        }
    }
}

/** The iOS tab bar's 49pt, near enough; Material's is 80dp. */
private val TAB_BAR_HEIGHT = 52.dp

private data class TabItem(
    val labelRes: Int,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
) {
    companion object {
        val Chats = TabItem(R.string.nav_chats, TabIcons.Chats, TabIcons.ChatsSelected)
        val Synaps = TabItem(R.string.nav_synaps, TabIcons.Synaps, TabIcons.SynapsSelected)
        val Calls = TabItem(R.string.nav_calls, Icons.Outlined.Phone, Icons.Filled.Phone)
        val Settings = TabItem(R.string.nav_settings, Icons.Outlined.Settings, Icons.Filled.Settings)
    }
}
