package com.construct.messenger.ui.screens.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
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
 * **Canon:** iOS moved to native `TabView`; Android uses Material3
 * `NavigationBar` (icon-only) as the canonical equivalent.
 */
@Composable
fun MainTabView(
    onNavigateToChat: (String) -> Unit,
    onScanQr: () -> Unit = {},
    settingsNavigation: SettingsNavigation = SettingsNavigation(),
    pendingChatViewModel: PendingChatViewModel = hiltViewModel(),
    startTab: Int = 0,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(startTab) }

    // A tapped message notification: open its chat, whether the app was cold or already here.
    val pendingChat by pendingChatViewModel.pending.collectAsStateWithLifecycle()
    LaunchedEffect(pendingChat) {
        if (pendingChat != null) pendingChatViewModel.take()?.let(onNavigateToChat)
    }

    val tabs = listOf(
        TabItem.Chats,
        TabItem.Synaps,
        TabItem.Calls,
        TabItem.Settings,
    )

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = CTColor.bg,
                contentColor = CTColor.text,
            ) {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = stringResource(tab.labelRes),
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = CTColor.accent,
                            unselectedIconColor = CTColor.textDim,
                            indicatorColor = CTColor.bg,
                        ),
                    )
                }
            }
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
                )
                1 -> SynapsScreen(
                    onNavigateToChat = onNavigateToChat,
                    onScanQr = onScanQr,
                )
                2 -> CallsScreen()
                3 -> SettingsRoute(navigation = settingsNavigation)
            }
        }
    }
}

private data class TabItem(
    val labelRes: Int,
    val icon: ImageVector,
) {
    companion object {
        val Chats = TabItem(R.string.nav_chats, Icons.Default.Chat)
        val Synaps = TabItem(R.string.nav_synaps, Icons.Default.Groups)
        val Calls = TabItem(R.string.nav_calls, Icons.Default.Phone)
        val Settings = TabItem(R.string.nav_settings, Icons.Default.Settings)
    }
}
