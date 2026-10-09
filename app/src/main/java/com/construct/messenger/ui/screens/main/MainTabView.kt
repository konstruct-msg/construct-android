package com.construct.messenger.ui.screens.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
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
import com.construct.messenger.viewmodel.PendingChatViewModel

/**
 * Root tab container: Chats | Synaps | Calls | Settings.
 *
 * Material's bottom navigation since 2026-10-09; Android no longer mirrors the iOS `TabView`
 * (vault `decisions/android-is-material-3.md`).
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

/**
 * Material `NavigationBar` (`docs/MATERIAL3_MIGRATION.md`, step 2): height, the selection
 * indicator (`secondaryContainer`), the label under each icon and the container
 * (`surfaceContainer`) are Material's. Outline icon when idle, filled when selected.
 */
@Composable
private fun TabBar(tabs: List<TabItem>, selected: Int, onSelect: (Int) -> Unit) {
    NavigationBar {
        tabs.forEachIndexed { index, tab ->
            val isSelected = selected == index
            NavigationBarItem(
                selected = isSelected,
                onClick = { onSelect(index) },
                icon = { Icon(if (isSelected) tab.selectedIcon else tab.icon, contentDescription = null) },
                label = { Text(stringResource(tab.labelRes)) },
            )
        }
    }
}

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
