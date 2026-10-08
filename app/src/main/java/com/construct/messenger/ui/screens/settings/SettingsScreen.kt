package com.construct.messenger.ui.screens.settings

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Laptop
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.QrCode
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.construct.messenger.BuildConfig
import com.construct.messenger.R
import com.construct.messenger.data.repository.OwnAccount
import com.construct.messenger.diagnostics.Diagnostics
import com.construct.messenger.recovery.HeldPhrase
import com.construct.messenger.recovery.RecoveryViewModel
import com.construct.messenger.ui.components.CTAvatar
import com.construct.messenger.ui.components.CTNavBar
import com.construct.messenger.ui.components.CTSectionGroup
import com.construct.messenger.ui.components.CTSep
import com.construct.messenger.ui.components.CTSettingsRow
import com.construct.messenger.ui.components.CTSettingsSectionHeader
import com.construct.messenger.ui.components.CTStatus
import com.construct.messenger.ui.components.CTStatusBadge
import com.construct.messenger.ui.components.ConnectionStatus
import com.construct.messenger.ui.components.rememberAvatar
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTFont
import com.construct.messenger.ui.theme.CTIcon
import com.construct.messenger.ui.theme.CTLayout
import com.construct.messenger.ui.theme.CTSpace
import com.construct.messenger.viewmodel.SettingsViewModel

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
        // Loading says nothing yet; a known "not set up", or a silent key not yet copied, shows it.
        recoveryMissing = recovery.needsBackup,
        recoveryHeld = recovery.held,
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
    recoveryHeld: HeldPhrase = HeldPhrase.NONE,
    // A parameter so the preview can pin it: the code is the commit count, and a screenshot
    // reference that read it would go stale with every commit.
    buildVersion: String = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
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
            // iOS: the warning is first, above the identity.
            if (recoveryMissing && !bannerDismissed) {
                RecoveryBanner(
                    held = recoveryHeld,
                    onSetUp = navigation.onRecoverySetup,
                    onDismiss = {
                        bannerDismissed = true
                        prefs.edit().putBoolean(KEY_RECOVERY_BANNER_DISMISSED, true).apply()
                    },
                )
            }

            CTSectionGroup {
                ProfileRow(account = account, onClick = navigation.onAccount)
            }

            CTSectionGroup {
                CTSettingsRow(
                    label = stringResource(R.string.settings_row_invite).uppercase(),
                    icon = Icons.Outlined.QrCode,
                    disclosure = true,
                    modifier = Modifier.clickable(onClick = navigation.onInvite),
                )
            }

            CTSectionGroup {
                CTSettingsRow(
                    label = stringResource(R.string.settings_row_devices).uppercase(),
                    icon = Icons.Outlined.Laptop,
                    disclosure = true,
                    modifier = Modifier.clickable(onClick = navigation.onDevices),
                )
                CTSep()
                CTSettingsRow(
                    label = stringResource(R.string.settings_row_appearance).uppercase(),
                    icon = Icons.Outlined.Brush,
                    disclosure = true,
                    modifier = Modifier.clickable(onClick = navigation.onAppearance),
                )
                CTSep()
                CTSettingsRow(
                    label = stringResource(R.string.settings_row_security).uppercase(),
                    icon = Icons.Outlined.Lock,
                    disclosure = true,
                    modifier = Modifier.clickable(onClick = navigation.onSecurity),
                )
                CTSep()
                CTSettingsRow(
                    label = stringResource(R.string.settings_row_notifications).uppercase(),
                    icon = Icons.Outlined.Notifications,
                    disclosure = true,
                    modifier = Modifier.clickable(onClick = navigation.onNotifications),
                )
                CTSep()
                CTSettingsRow(
                    label = stringResource(R.string.settings_row_network).uppercase(),
                    icon = Icons.Outlined.Public,
                    status = connection.toStatus(),
                    disclosure = true,
                    modifier = Modifier.clickable(onClick = navigation.onNetwork),
                )
                CTSep()
                CTSettingsRow(
                    label = stringResource(R.string.settings_row_drafts).uppercase(),
                    icon = Icons.Outlined.Folder,
                    disclosure = true,
                    modifier = Modifier.clickable(onClick = navigation.onDrafts),
                )
            }

            CTSectionGroup {
                CTSettingsRow(
                    label = stringResource(R.string.orientation_settings_replay).uppercase(),
                    icon = Icons.AutoMirrored.Outlined.MenuBook,
                    disclosure = true,
                    modifier = Modifier.clickable(onClick = navigation.onOrientation),
                )
                CTSep()
                // iOS `versionDisplayString`: `v0.2.0 (2)`, and ` BETA` in orange on a
                // non-production build.
                CTSettingsRow(
                    label = stringResource(R.string.settings_row_version).uppercase(),
                    value = buildVersion +
                        if (BuildConfig.DEBUG) " " + stringResource(R.string.build_channel_beta).uppercase() else "",
                    valueColor = if (BuildConfig.DEBUG) CTColor.warning else CTColor.textDim,
                    icon = Icons.Outlined.Info,
                )
            }

            // iOS shows this in DEBUG and internal builds; Android writes logs in debug only.
            if (Diagnostics.isEnabled) {
                Column {
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
            image = rememberAvatar(account?.avatar),
            size = 56.dp,
        )
        Spacer(Modifier.width(CTLayout.edgePad))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = (account?.displayName ?: stringResource(R.string.settings_row_account_fallback)).uppercase(),
                style = CTFont.ui(15, FontWeight.Bold),
                color = CTColor.text,
            )
            Text(
                text = account?.username?.takeIf { it.isNotEmpty() }?.let { "@$it" }
                    ?: stringResource(R.string.username_not_set),
                style = CTFont.secondary,
                color = CTColor.textDim,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                CTStatusBadge(status = if (searchable) CTStatus.ON else CTStatus.OFF, size = 11.dp)
                Spacer(Modifier.width(5.dp))
                Text(
                    text = stringResource(
                        if (searchable) R.string.searchable_indicator else R.string.searchable_indicator_off,
                    ),
                    style = CTFont.caption,
                    color = if (searchable) CTColor.accent else CTColor.textDim,
                )
            }
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = CTColor.accent,
            modifier = Modifier.size(CTIcon.nav),
        )
    }
}

internal fun ConnectionStatus.toStatus(): CTStatus = when (this) {
    ConnectionStatus.CONNECTED -> CTStatus.OK
    ConnectionStatus.CONNECTING -> CTStatus.BUSY
    ConnectionStatus.DISCONNECTED -> CTStatus.ERROR
    ConnectionStatus.UNKNOWN -> CTStatus.UNKNOWN
}

/**
 * iOS `SettingsView.recoveryBanner`: the phrase is missing, set it up or put this away. A key made
 * silently and not yet copied is the common case since 2026-10-04; "not configured" is left for
 * devices that could not make one.
 */
@Composable
private fun RecoveryBanner(held: HeldPhrase, onSetUp: () -> Unit, onDismiss: () -> Unit) {
    val (title, subtitle) = when (held) {
        HeldPhrase.HELD -> R.string.recovery_backup_pending_title to R.string.recovery_backup_pending_subtitle
        HeldPhrase.LOST -> R.string.recovery_backup_lost_title to R.string.recovery_backup_lost_body
        HeldPhrase.NONE -> R.string.recovery_banner_title to R.string.recovery_banner_subtitle
    }
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
            modifier = Modifier.size(CTIcon.nav),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CTSpace.xs)) {
            Text(
                text = stringResource(title).uppercase(),
                style = CTFont.badge,
                color = CTColor.danger,
            )
            Text(
                text = stringResource(subtitle),
                style = CTFont.secondary,
                color = CTColor.textDim,
            )
            // Lost: there is nothing left to set up, only the fact to state.
            if (held != HeldPhrase.LOST) {
                Row(
                    modifier = Modifier.clickable(onClick = onSetUp).padding(vertical = CTSpace.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = stringResource(R.string.recovery_banner_action), style = CTFont.badge, color = CTColor.accent)
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = CTColor.accent,
                        modifier = Modifier.size(CTIcon.row),
                    )
                }
            }
        }
        Icon(
            imageVector = Icons.Default.Close,
            contentDescription = stringResource(R.string.close),
            tint = CTColor.textDim,
            modifier = Modifier
                .size(CTIcon.nav)
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
        buildVersion = "v0.0.0 (1)",
    )
}
