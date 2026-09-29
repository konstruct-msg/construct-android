package com.construct.messenger.ui.screens.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.construct.messenger.BuildConfig
import com.construct.messenger.R
import com.construct.messenger.data.repository.OwnAccount
import com.construct.messenger.diagnostics.Diagnostics
import com.construct.messenger.ui.components.CTAvatar
import com.construct.messenger.ui.components.CTNavBar
import com.construct.messenger.ui.components.CTSectionGroup
import com.construct.messenger.ui.components.CTSep
import com.construct.messenger.ui.components.CTSettingsRow
import com.construct.messenger.ui.components.CTSettingsSectionHeader
import com.construct.messenger.ui.components.CTStatus
import com.construct.messenger.ui.components.CTStatusBadge
import com.construct.messenger.ui.components.ConnectionStatus
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTLayout
import com.construct.messenger.ui.theme.ctBold
import com.construct.messenger.ui.theme.ctRegular
import com.construct.messenger.viewmodel.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Callbacks for the rows that open another app screen. */
data class SettingsNavigation(
    val onAccount: () -> Unit = {},
    val onInvite: () -> Unit = {},
    val onSecurity: () -> Unit = {},
    val onOrientation: () -> Unit = {},
)

@Composable
fun SettingsRoute(
    navigation: SettingsNavigation,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    // Each time the tab is shown: an alias changed on the Account screen appears on return.
    LaunchedEffect(Unit) { viewModel.refresh() }
    SettingsScreen(account = ui.account, connection = ui.connection, navigation = navigation)
}

/**
 * Settings root: identity, invite, the settings that exist, about.
 *
 * **Canon:** iOS `SettingsView` (compact layout) — cards separated by space, no section headers,
 * uppercase row labels. Rows appear only for what Android actually has: a row that opens
 * nothing reads as broken. Missing against iOS: linked devices, appearance, data & storage,
 * transcription, drafts, recovery (and its banner). Diagnostics here is the log half of iOS
 * `DiagnosticsView` only, and like it exists in debug builds only.
 */
@Composable
fun SettingsScreen(
    account: OwnAccount?,
    connection: ConnectionStatus,
    navigation: SettingsNavigation,
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CTColor.bg)
            // Edge-to-edge: the screen keeps itself clear of the bars. Inside MainTabView the
            // Scaffold has already padded and consumed them, so these add nothing there.
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        CTNavBar(title = stringResource(R.string.settings_title))

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(vertical = LIST_SPACING / 2),
            verticalArrangement = Arrangement.spacedBy(LIST_SPACING),
        ) {
            CTSectionGroup {
                ProfileRow(account = account, onClick = navigation.onAccount)
            }

            CTSectionGroup {
                CTSettingsRow(
                    label = stringResource(R.string.settings_row_invite).uppercase(),
                    icon = Icons.Default.QrCode,
                    disclosure = true,
                    modifier = Modifier.clickable(onClick = navigation.onInvite),
                )
            }

            CTSectionGroup {
                CTSettingsRow(
                    label = stringResource(R.string.settings_row_security).uppercase(),
                    icon = Icons.Default.Lock,
                    disclosure = true,
                    modifier = Modifier.clickable(onClick = navigation.onSecurity),
                )
                CTSep()
                // Android owns notification settings (channels, lock screen, sound); a copy of
                // them here would drift from the real ones.
                CTSettingsRow(
                    label = stringResource(R.string.settings_row_notifications).uppercase(),
                    icon = Icons.Default.Notifications,
                    disclosure = true,
                    modifier = Modifier.clickable { context.openNotificationSettings() },
                )
                CTSep()
                // Status only: iOS's Network screen is VEIL, which Android does not have yet.
                CTSettingsRow(
                    label = stringResource(R.string.settings_row_network).uppercase(),
                    icon = Icons.Default.Public,
                    status = connection.toStatus(),
                )
            }

            if (Diagnostics.isEnabled) DiagnosticsSection()

            CTSectionGroup {
                CTSettingsRow(
                    label = stringResource(R.string.orientation_settings_replay).uppercase(),
                    icon = Icons.AutoMirrored.Filled.MenuBook,
                    disclosure = true,
                    modifier = Modifier.clickable(onClick = navigation.onOrientation),
                )
                CTSep()
                CTSettingsRow(
                    label = stringResource(R.string.settings_row_version).uppercase(),
                    value = BuildConfig.VERSION_NAME,
                    valueColor = CTColor.textDim,
                    icon = Icons.Default.Info,
                )
            }
        }
    }
}

@Composable
private fun ProfileRow(account: OwnAccount?, onClick: () -> Unit) {
    val searchable = account?.discoverable == true
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(CTLayout.edgePad),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CTAvatar(
            userId = account?.userId.orEmpty(),
            displayName = account?.displayName.orEmpty(),
            size = 56.dp,
        )
        Spacer(Modifier.width(CTLayout.edgePad))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = (account?.displayName ?: stringResource(R.string.settings_row_account_fallback)).uppercase(),
                style = ctBold(15),
                color = CTColor.text,
            )
            Text(
                text = account?.username?.takeIf { it.isNotEmpty() }?.let { "@$it" }
                    ?: stringResource(R.string.username_not_set),
                style = ctRegular(12),
                color = CTColor.textDim,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                CTStatusBadge(status = if (searchable) CTStatus.ON else CTStatus.OFF, size = 11.dp)
                Spacer(Modifier.width(5.dp))
                Text(
                    text = stringResource(
                        if (searchable) R.string.searchable_indicator else R.string.searchable_indicator_off,
                    ),
                    style = ctRegular(11),
                    color = if (searchable) CTColor.accent else CTColor.textDim,
                )
            }
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = CTColor.accent,
            modifier = Modifier.size(20.dp),
        )
    }
}

private fun ConnectionStatus.toStatus(): CTStatus = when (this) {
    ConnectionStatus.CONNECTED -> CTStatus.OK
    ConnectionStatus.CONNECTING -> CTStatus.BUSY
    ConnectionStatus.DISCONNECTED -> CTStatus.ERROR
    ConnectionStatus.UNKNOWN -> CTStatus.UNKNOWN
}

private fun Context.openNotificationSettings() {
    startActivity(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

/** Share and clear the log files. Sizes are read off the main thread: they are file stats. */
@Composable
private fun DiagnosticsSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var bytes by remember { mutableLongStateOf(-1L) }
    suspend fun refresh() {
        bytes = withContext(Dispatchers.IO) { Diagnostics.collector?.totalBytes() ?: 0L }
    }
    LaunchedEffect(Unit) { refresh() }

    Column {
        CTSettingsSectionHeader(title = stringResource(R.string.diagnostics_title), color = DEBUG_ORANGE)
        CTSectionGroup {
            CTSettingsRow(
                label = stringResource(R.string.diagnostics_share_logs).uppercase(),
                value = if (bytes >= 0) Formatter.formatShortFileSize(context, bytes) else "",
                valueColor = CTColor.textDim,
                icon = Icons.Default.Share,
                modifier = Modifier.clickable {
                    scope.launch {
                        // The archive is built from files: not on the main thread.
                        withContext(Dispatchers.IO) { Diagnostics.collector?.flush() }
                        Diagnostics.share(context)
                    }
                },
            )
            CTSep()
            CTSettingsRow(
                label = stringResource(R.string.diagnostics_clear_logs).uppercase(),
                icon = Icons.Default.Delete,
                isDestructive = true,
                modifier = Modifier.clickable {
                    scope.launch {
                        withContext(Dispatchers.IO) { Diagnostics.collector?.clear() }
                        refresh()
                    }
                },
            )
        }
    }
}

/** iOS marks debug-only surfaces `.orange`. */
private val DEBUG_ORANGE = Color(0xFFFF9500)

/** iOS `SettingsRootLayout.listSpacing`. */
private val LIST_SPACING = 30.dp

@Preview(backgroundColor = 0xFF090909, showBackground = true, heightDp = 800, widthDp = 360)
@Composable
private fun SettingsScreenPreview() {
    SettingsScreen(
        account = OwnAccount(
            userId = "14f28d31-aaaa-bbbb-cccc-000000000099",
            displayName = "soft lion",
            username = "",
            discoverable = false,
            fingerprint = "A1B2 C3D4 E5F6 0718",
        ),
        connection = ConnectionStatus.CONNECTED,
        navigation = SettingsNavigation(),
    )
}
