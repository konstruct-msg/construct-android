package com.construct.messenger.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.construct.messenger.R
import com.construct.messenger.ui.components.CTAvatar
import com.construct.messenger.ui.components.CTNavBar
import com.construct.messenger.ui.components.CTSectionGroup
import com.construct.messenger.ui.components.CTSep
import com.construct.messenger.ui.components.CTSettingsRow
import com.construct.messenger.ui.components.CTSettingsSectionHeader
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CornerRadius
import com.construct.messenger.ui.theme.HairlineBorder
import com.construct.messenger.ui.theme.ctBold
import com.construct.messenger.ui.theme.ctRegular
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.construct.messenger.viewmodel.SettingsEvent
import com.construct.messenger.viewmodel.SettingsViewModel

/**
 * Settings hub on fake profile data.
 *
 * **Canon:** iOS SettingsView / `ANDROID_ONBOARDING.md` §5.5.
 * Sub-screen navigation is stubbed (no-op callbacks). No ViewModel — see #23.
 */
private data class FakeSettingsProfile(
    val userId: String = "14f28d31-aaaa-bbbb-cccc-000000000099",
    val displayName: String = "Silent Fox",
    val username: String = "silent_fox",
    val discoverable: Boolean = true,
    val recoveryConfigured: Boolean = false,
    val appVersion: String = "0.1.0-dev",
)

private val FakeProfile = FakeSettingsProfile()

@Composable
fun SettingsRoute(
    onSignedOut: () -> Unit,
    onNavigateBack: (() -> Unit)? = null,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.eventsFlow.collect { event ->
            if (event is SettingsEvent.SignedOut) onSignedOut()
        }
    }
    SettingsScreen(
        onNavigateBack = onNavigateBack,
        onSignOut = viewModel::signOut,
        onToggleDiscoverable = viewModel::toggleDiscoverable,
        liveDisplayName = ui.profile?.displayName?.takeIf { it.isNotBlank() },
        liveUsername = ui.profile?.username?.takeIf { it.isNotBlank() },
        liveUserId = ui.profile?.userId?.takeIf { it.isNotBlank() },
        liveDiscoverable = ui.discoverable,
    )
}

@Composable
fun SettingsScreen(
    onNavigateBack: (() -> Unit)? = null,
    onProfileClick: () -> Unit = {},
    onShareInviteClick: () -> Unit = {},
    onAppearanceClick: () -> Unit = {},
    onNetworkClick: () -> Unit = {},
    onSecurityClick: () -> Unit = {},
    onLicensesClick: () -> Unit = {},
    onDiagnosticsClick: () -> Unit = {},
    onSignOut: () -> Unit = {},
    onToggleDiscoverable: () -> Unit = {},
    liveDisplayName: String? = null,
    liveUsername: String? = null,
    liveUserId: String? = null,
    liveDiscoverable: Boolean? = null,
) {
    val profile = FakeProfile.copy(
        userId = liveUserId ?: FakeProfile.userId,
        displayName = liveDisplayName ?: FakeProfile.displayName,
        username = liveUsername ?: FakeProfile.username,
        discoverable = liveDiscoverable ?: FakeProfile.discoverable,
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CTColor.bg)
            // Edge-to-edge: the screen keeps itself clear of the bars. Inside MainTabView the
            // Scaffold has already padded and consumed them, so these add nothing there.
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        CTNavBar(
            title = stringResource(R.string.settings_title),
            showBack = onNavigateBack != null,
            onBack = { onNavigateBack?.invoke() },
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            if (!profile.recoveryConfigured) {
                RecoveryBanner()
            }

            CTSettingsSectionHeader(title = stringResource(R.string.settings_section_profile))
            CTSectionGroup {
                SettingsProfileRow(
                    profile = profile,
                    onClick = onProfileClick,
                )
            }

            CTSettingsSectionHeader(title = stringResource(R.string.settings_section_share))
            CTSectionGroup {
                CTSettingsRow(
                    label = stringResource(R.string.settings_row_share_invite),
                    icon = Icons.Default.Share,
                    isAction = true,
                    modifier = Modifier.clickable(onClick = onShareInviteClick),
                )
            }

            CTSettingsSectionHeader(title = stringResource(R.string.settings_section_settings))
            CTSectionGroup {
                CTSettingsRow(
                    label = stringResource(R.string.settings_row_appearance),
                    icon = Icons.Default.Palette,
                    isAction = true,
                    modifier = Modifier.clickable(onClick = onAppearanceClick),
                )
                CTSep()
                CTSettingsRow(
                    label = stringResource(R.string.settings_row_network),
                    icon = Icons.Default.Wifi,
                    isAction = true,
                    modifier = Modifier.clickable(onClick = onNetworkClick),
                )
                CTSep()
                CTSettingsRow(
                    label = stringResource(R.string.settings_row_security),
                    icon = Icons.Default.Lock,
                    isAction = true,
                    modifier = Modifier.clickable(onClick = onSecurityClick),
                )
            }

            CTSettingsSectionHeader(title = stringResource(R.string.settings_section_about))
            CTSectionGroup {
                CTSettingsRow(
                    label = stringResource(R.string.settings_row_version),
                    value = profile.appVersion,
                    icon = Icons.Default.Info,
                )
                CTSep()
                CTSettingsRow(
                    label = stringResource(R.string.settings_row_licenses),
                    icon = Icons.Default.Description,
                    isAction = true,
                    modifier = Modifier.clickable(onClick = onLicensesClick),
                )
            }

            CTSettingsSectionHeader(title = stringResource(R.string.settings_section_developer))
            CTSectionGroup {
                CTSettingsRow(
                    label = stringResource(R.string.settings_row_diagnostics),
                    icon = Icons.Default.BugReport,
                    isAction = true,
                    modifier = Modifier.clickable(onClick = onDiagnosticsClick),
                )
            }

            CTSectionGroup {
                CTSettingsRow(
                    label = stringResource(R.string.settings_row_discoverable),
                    value = if (profile.discoverable) {
                        stringResource(R.string.settings_discoverable_on)
                    } else {
                        stringResource(R.string.settings_discoverable_off)
                    },
                    isAction = true,
                    modifier = Modifier.clickable(onClick = onToggleDiscoverable),
                )
                CTSep()
                CTSettingsRow(
                    label = stringResource(R.string.settings_row_sign_out),
                    isDestructive = true,
                    isAction = true,
                    modifier = Modifier.clickable(onClick = onSignOut),
                )
            }
        }
    }
}

@Composable
private fun SettingsProfileRow(
    profile: FakeSettingsProfile,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CTAvatar(
            userId = profile.userId,
            displayName = profile.displayName,
            size = 44.dp,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = profile.displayName,
                style = ctBold(13),
                color = CTColor.text,
            )
            Text(
                text = stringResource(R.string.settings_username_format, profile.username),
                style = ctRegular(12),
                color = CTColor.textDim,
            )
            if (profile.discoverable) {
                Text(
                    text = stringResource(R.string.settings_discoverable_on),
                    style = ctRegular(11),
                    color = CTColor.accent,
                )
            }
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = CTColor.textDim,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun RecoveryBanner() {
    val shape = RoundedCornerShape(CornerRadius.small)
    Text(
        text = stringResource(R.string.settings_recovery_banner),
        style = ctRegular(13),
        color = CTColor.danger,
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(top = 16.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(CTColor.danger.copy(alpha = 0.12f))
            .border(HairlineBorder, CTColor.danger.copy(alpha = 0.4f), shape)
            .padding(12.dp),
    )
}

@Preview(backgroundColor = 0xFF090909, showBackground = true, heightDp = 800, widthDp = 360)
@Composable
private fun SettingsScreenPreview() {
    SettingsScreen()
}
