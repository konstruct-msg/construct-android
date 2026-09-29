package com.construct.messenger.ui.screens.settings

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.construct.messenger.recovery.RecoveryStage
import com.construct.messenger.recovery.RecoveryViewModel

/** Callbacks for the rows that open another app screen. */
data class SettingsNavigation(
    val onAccount: () -> Unit = {},
    val onInvite: () -> Unit = {},
    val onDevices: () -> Unit = {},
    val onAppearance: () -> Unit = {},
    val onSecurity: () -> Unit = {},
    val onNotifications: () -> Unit = {},
    val onNetwork: () -> Unit = {},
    val onDrafts: () -> Unit = {},
    val onOrientation: () -> Unit = {},
    val onDiagnostics: () -> Unit = {},
    val onRecoverySetup: () -> Unit = {},
)

@Composable
fun SettingsRoute(
    navigation: SettingsNavigation,
    viewModel: SettingsViewModel = hiltViewModel(),
    recoveryViewModel: RecoveryViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val recovery by recoveryViewModel.uiState.collectAsStateWithLifecycle()
    // Each time the tab is shown: an alias changed on the Account screen appears on return, and a
    // phrase set up behind the banner takes it away.
    LaunchedEffect(Unit) {
        viewModel.refresh()
        recoveryViewModel.refresh()
    }
    SettingsScreen(
        account = ui.account,
        connection = ui.connection,
        navigation = navigation,
        // Loading says nothing yet; only a known "not set up" shows the banner.
        recoveryMissing = recovery.stage == RecoveryStage.Explain || recovery.stage == RecoveryStage.Confirm,
    )
}

/**
 * Settings root: identity, invite, the settings that exist, about.
 *
 * **Canon:** iOS `SettingsView` (compact layout) — cards separated by space, no section headers,
 * uppercase row labels. Rows appear only for what Android actually has: a row that opens
 * nothing reads as broken. Missing against iOS: data & storage and transcription — both wait
 * for media, and a row that opens nothing reads as broken. Diagnostics is the log half of iOS
 * `DiagnosticsView`, in debug builds only.
 */
@Composable
fun SettingsScreen(
    account: OwnAccount?,
    connection: ConnectionStatus,
    navigation: SettingsNavigation,
    recoveryMissing: Boolean = false,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE) }
    var bannerDismissed by remember { mutableStateOf(prefs.getBoolean(KEY_RECOVERY_BANNER_DISMISSED, false)) }
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

            if (recoveryMissing && !bannerDismissed) {
                RecoveryBanner(
                    onSetUp = navigation.onRecoverySetup,
                    onDismiss = {
                        bannerDismissed = true
                        prefs.edit().putBoolean(KEY_RECOVERY_BANNER_DISMISSED, true).apply()
                    },
                )
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
                    label = stringResource(R.string.settings_row_devices).uppercase(),
                    icon = Icons.Default.Laptop,
                    disclosure = true,
                    modifier = Modifier.clickable(onClick = navigation.onDevices),
                )
                CTSep()
                CTSettingsRow(
                    label = stringResource(R.string.settings_row_appearance).uppercase(),
                    icon = Icons.Default.Brush,
                    disclosure = true,
                    modifier = Modifier.clickable(onClick = navigation.onAppearance),
                )
                CTSep()
                CTSettingsRow(
                    label = stringResource(R.string.settings_row_security).uppercase(),
                    icon = Icons.Default.Lock,
                    disclosure = true,
                    modifier = Modifier.clickable(onClick = navigation.onSecurity),
                )
                CTSep()
                CTSettingsRow(
                    label = stringResource(R.string.settings_row_notifications).uppercase(),
                    icon = Icons.Default.Notifications,
                    disclosure = true,
                    modifier = Modifier.clickable(onClick = navigation.onNotifications),
                )
                CTSep()
                CTSettingsRow(
                    label = stringResource(R.string.settings_row_network).uppercase(),
                    icon = Icons.Default.Public,
                    status = connection.toStatus(),
                    disclosure = true,
                    modifier = Modifier.clickable(onClick = navigation.onNetwork),
                )
                CTSep()
                CTSettingsRow(
                    label = stringResource(R.string.settings_row_drafts).uppercase(),
                    icon = Icons.Default.Folder,
                    disclosure = true,
                    modifier = Modifier.clickable(onClick = navigation.onDrafts),
                )
            }

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

            // iOS shows this in DEBUG and internal builds; Android writes logs in debug only.
            if (Diagnostics.isEnabled) {
                Column {
                    CTSettingsSectionHeader(title = stringResource(R.string.settings_section_developer), color = DEBUG_ORANGE)
                    CTSectionGroup {
                        CTSettingsRow(
                            label = stringResource(R.string.diagnostics_logs).uppercase(),
                            labelColor = DEBUG_ORANGE,
                            disclosure = true,
                            modifier = Modifier.clickable(onClick = navigation.onDiagnostics),
                        )
                    }
                }
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

internal fun ConnectionStatus.toStatus(): CTStatus = when (this) {
    ConnectionStatus.CONNECTED -> CTStatus.OK
    ConnectionStatus.CONNECTING -> CTStatus.BUSY
    ConnectionStatus.DISCONNECTED -> CTStatus.ERROR
    ConnectionStatus.UNKNOWN -> CTStatus.UNKNOWN
}

/** iOS `SettingsView.recoveryBanner`: the phrase is missing, set it up or put this away. */
@Composable
private fun RecoveryBanner(onSetUp: () -> Unit, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .padding(horizontal = CTLayout.edgePad)
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(CTColor.danger.copy(alpha = 0.06f))
            .border(1.dp, CTColor.danger.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
            .padding(CTLayout.edgePad),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = Icons.Default.Error,
            contentDescription = null,
            tint = CTColor.danger,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.recovery_banner_title).uppercase(),
                style = ctBold(11),
                color = CTColor.danger,
            )
            Text(
                text = stringResource(R.string.recovery_banner_subtitle),
                style = ctRegular(12),
                color = CTColor.textDim,
            )
            Row(
                modifier = Modifier.clickable(onClick = onSetUp).padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = stringResource(R.string.recovery_banner_action), style = ctBold(11), color = CTColor.accent)
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = CTColor.accent,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        Icon(
            imageVector = Icons.Default.Close,
            contentDescription = stringResource(R.string.close),
            tint = CTColor.textDim,
            modifier = Modifier
                .size(18.dp)
                .clickable(onClick = onDismiss),
        )
    }
}

private const val SETTINGS_PREFS = "settings_prefs"
private const val KEY_RECOVERY_BANNER_DISMISSED = "recovery_banner_dismissed"

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
