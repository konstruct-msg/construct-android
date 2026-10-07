package com.construct.messenger.ui.screens.settings

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DevicesOther
import androidx.compose.material.icons.filled.HighlightOff
import androidx.compose.material.icons.filled.PersonOff
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PhoneIphone
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.construct.messenger.R
import com.construct.messenger.data.model.DevicePlatform
import com.construct.messenger.data.model.LinkedDevice
import com.construct.messenger.ui.components.CTConfirmDialog
import com.construct.messenger.ui.components.CTNavBar
import com.construct.messenger.ui.components.CTSectionGroup
import com.construct.messenger.ui.components.CTSep
import com.construct.messenger.ui.components.CTSettingsSectionHeader
import com.construct.messenger.ui.components.ConstructActionRow
import com.construct.messenger.ui.components.ConstructRowRole
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTFont
import com.construct.messenger.ui.theme.CTIcon
import com.construct.messenger.ui.theme.CTLayout
import com.construct.messenger.ui.theme.CTSpace
import com.construct.messenger.viewmodel.AccountEvent
import com.construct.messenger.viewmodel.DevicesUiState
import com.construct.messenger.viewmodel.DevicesViewModel
import java.util.Date

/** Which confirmation is open. */
private sealed interface DevicesConfirm {
    data class Revoke(val device: LinkedDevice) : DevicesConfirm
    data object SignOutThis : DevicesConfirm
    data object SignOutOthers : DevicesConfirm
    data object SignOutAll : DevicesConfirm
}

@Composable
fun DevicesRoute(
    onNavigateBack: () -> Unit,
    onSignedOut: () -> Unit,
    viewModel: DevicesViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.eventsFlow.collect { if (it is AccountEvent.SignedOut) onSignedOut() }
    }
    var confirm by remember { mutableStateOf<DevicesConfirm?>(null) }
    confirm?.let { open ->
        val dismiss = { confirm = null }
        when (open) {
            is DevicesConfirm.Revoke -> CTConfirmDialog(
                title = stringResource(R.string.device_revoke_confirm_title),
                message = stringResource(R.string.device_revoke_confirm_message, open.device.label()),
                confirmLabel = stringResource(R.string.device_revoke),
                dismissLabel = stringResource(R.string.action_cancel),
                isDestructive = true,
                onConfirm = { dismiss(); viewModel.revoke(open.device) },
                onDismiss = dismiss,
            )
            DevicesConfirm.SignOutThis -> CTConfirmDialog(
                title = stringResource(R.string.sign_out_this_device),
                message = stringResource(R.string.sign_out_this_device_message),
                confirmLabel = stringResource(R.string.logout_confirm_action),
                dismissLabel = stringResource(R.string.action_cancel),
                isDestructive = true,
                onConfirm = { dismiss(); viewModel.signOut(allDevices = false) },
                onDismiss = dismiss,
            )
            DevicesConfirm.SignOutOthers -> CTConfirmDialog(
                title = stringResource(R.string.sign_out_other_devices),
                message = stringResource(R.string.sign_out_other_devices_message),
                confirmLabel = stringResource(R.string.sign_out_other_devices),
                dismissLabel = stringResource(R.string.action_cancel),
                isDestructive = true,
                onConfirm = { dismiss(); viewModel.revokeOthers() },
                onDismiss = dismiss,
            )
            DevicesConfirm.SignOutAll -> CTConfirmDialog(
                title = stringResource(R.string.sign_out_all_devices),
                message = stringResource(R.string.sign_out_all_devices_message),
                confirmLabel = stringResource(R.string.sign_out_all_devices),
                dismissLabel = stringResource(R.string.action_cancel),
                isDestructive = true,
                onConfirm = { dismiss(); viewModel.signOut(allDevices = true) },
                onDismiss = dismiss,
            )
        }
    }
    ui.error?.let { message ->
        CTConfirmDialog(
            title = stringResource(R.string.error),
            message = stringResource(message),
            confirmLabel = stringResource(R.string.ok),
            dismissLabel = null,
            onConfirm = viewModel::dismissError,
            onDismiss = viewModel::dismissError,
        )
    }
    DevicesScreen(
        ui = ui,
        onNavigateBack = onNavigateBack,
        onConfirm = { confirm = it },
    )
}

/**
 * The account's devices and the ways to end their sessions.
 *
 * **Canon:** iOS `DevicesView` — this device first, the others with a remove control, then
 * session management. Not ported: linking a new device and history re-transfer, which iOS offers
 * in debug and internal builds only.
 */
@Composable
private fun DevicesScreen(
    ui: DevicesUiState,
    onNavigateBack: () -> Unit,
    onConfirm: (DevicesConfirm) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CTColor.bg)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        CTNavBar(
            title = stringResource(R.string.linked_devices),
            showBack = true,
            onBack = onNavigateBack,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = CTLayout.edgePad),
        ) {
            if (ui.loading && ui.devices.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(CTSpace.xxl), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = CTColor.accent, strokeWidth = 2.dp, modifier = Modifier.size(CTIcon.control))
                }
                return@Column
            }

            ui.current?.let { current ->
                CTSettingsSectionHeader(title = stringResource(R.string.this_device))
                CTSectionGroup { DeviceRow(device = current, onRevoke = null) }
            }

            val others = ui.others
            if (others.isNotEmpty()) {
                CTSettingsSectionHeader(title = stringResource(R.string.other_devices))
                CTSectionGroup {
                    others.forEachIndexed { index, device ->
                        if (index > 0) CTSep()
                        DeviceRow(
                            device = device,
                            // The server refuses to revoke the primary device; no control that
                            // can only fail.
                            onRevoke = if (device.isPrimary) null else ({ onConfirm(DevicesConfirm.Revoke(device)) }),
                        )
                    }
                }
                if (others.size > 1) Hint(stringResource(R.string.other_devices_hint))
            }

            CTSettingsSectionHeader(title = stringResource(R.string.session_management))
            // iOS: each a destructive `ConstructActionRow` card of its own, not rows in a group.
            Column(
                modifier = Modifier.padding(horizontal = CTLayout.edgePad),
                verticalArrangement = Arrangement.spacedBy(CTSpace.xs),
            ) {
                ConstructActionRow(
                    icon = Icons.AutoMirrored.Filled.Logout,
                    title = stringResource(R.string.sign_out_this_device),
                    role = ConstructRowRole.DESTRUCTIVE,
                    onClick = { onConfirm(DevicesConfirm.SignOutThis) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (others.isNotEmpty()) {
                    ConstructActionRow(
                        icon = Icons.Default.PersonOff,
                        title = stringResource(R.string.sign_out_other_devices),
                        role = ConstructRowRole.DESTRUCTIVE,
                        onClick = { onConfirm(DevicesConfirm.SignOutOthers) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                ConstructActionRow(
                    icon = Icons.Default.HighlightOff,
                    title = stringResource(R.string.sign_out_all_devices),
                    role = ConstructRowRole.DESTRUCTIVE,
                    onClick = { onConfirm(DevicesConfirm.SignOutAll) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Hint(stringResource(R.string.sign_out_all_hint))
        }
    }
}

@Composable
private fun DeviceRow(device: LinkedDevice, onRevoke: (() -> Unit)?) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = CTLayout.edgePad, vertical = CTSpace.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = device.platform.icon,
            contentDescription = null,
            tint = if (device.isCurrent) CTColor.accent else CTColor.textDim,
            modifier = Modifier.size(CTIcon.navLg),
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = device.label(), style = CTFont.ui(15, FontWeight.Bold), color = CTColor.text)
            // The id in the eight characters every log line prints: a name does not identify a
            // device, and relinking the same one yields rows with the same name.
            SelectionContainer {
                Text(
                    text = device.shortId,
                    style = CTFont.mono(11),
                    color = CTColor.textDim,
                )
            }
            if (device.isCurrent) {
                Text(text = "● " + stringResource(R.string.device_active_now), style = CTFont.secondary, color = CTColor.accent)
            } else if (device.createdAt > 0) {
                // The server keeps no last-seen (migration 013), so the date added is all there is.
                val added = DateFormat.getMediumDateFormat(context).format(Date(device.createdAt * 1000))
                Text(text = stringResource(R.string.device_added, added), style = CTFont.secondary, color = CTColor.textDim)
            }
        }
        if (onRevoke != null) {
            Icon(
                imageVector = Icons.Default.Cancel,
                contentDescription = stringResource(R.string.device_revoke),
                tint = CTColor.danger,
                modifier = Modifier
                    .size(CTIcon.control)
                    .clickable(onClick = onRevoke),
            )
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = CTFont.caption,
        color = CTColor.textDim,
        modifier = Modifier.padding(horizontal = CTLayout.edgePad * 2, vertical = CTSpace.s),
    )
}

@Composable
private fun LinkedDevice.label(): String = name ?: stringResource(R.string.device_unnamed)

private val DevicePlatform?.icon: ImageVector
    get() = when (this) {
        DevicePlatform.IOS -> Icons.Default.PhoneIphone
        DevicePlatform.ANDROID -> Icons.Default.PhoneAndroid
        DevicePlatform.DESKTOP -> Icons.Default.Computer
        DevicePlatform.OTHER, null -> Icons.Default.DevicesOther
    }

@Preview(backgroundColor = 0xFF090909, showBackground = true, heightDp = 800, widthDp = 360)
@Composable
private fun DevicesScreenPreview() {
    DevicesScreen(
        ui = DevicesUiState(
            devices = listOf(
                LinkedDevice("0d84b416-aaaa", "Pixel", DevicePlatform.ANDROID, 1_758_000_000, isCurrent = true, isPrimary = false),
                LinkedDevice("f1a3d746-bbbb", "iPhone", DevicePlatform.IOS, 1_757_000_000, isCurrent = false, isPrimary = true),
                LinkedDevice("38e653ec-cccc", null, null, 1_756_000_000, isCurrent = false, isPrimary = false),
            ),
        ),
        onNavigateBack = {},
        onConfirm = {},
    )
}
