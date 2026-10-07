package com.construct.messenger.ui.screens.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.construct.messenger.R
import com.construct.messenger.data.repository.NotificationSettingsRepository
import com.construct.messenger.ui.components.CTNavBar
import com.construct.messenger.ui.components.CTSectionGroup
import com.construct.messenger.ui.components.CTSep
import com.construct.messenger.ui.components.CTSettingsRow
import com.construct.messenger.ui.components.CTSettingsSectionHeader
import com.construct.messenger.ui.components.CTStatus
import com.construct.messenger.ui.components.CTStatusBadge
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTFont
import com.construct.messenger.ui.theme.CTLayout
import com.construct.messenger.ui.theme.CTSpace
import com.construct.messenger.viewmodel.NotificationsViewModel

/** What the system allows: iOS `UNAuthorizationStatus`, reduced to what Android can tell. */
private enum class SystemPermission { ALLOWED, NOT_ASKED, DENIED }

@Composable
fun NotificationsRoute(
    onNavigateBack: () -> Unit,
    viewModel: NotificationsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val enabled by viewModel.enabled.collectAsStateWithLifecycle()
    var permission by remember { mutableStateOf(context.systemPermission(asked = false)) }
    var asked by remember { mutableStateOf(false) }

    // The system settings are one tap away; what the person changed there shows on return.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permission = context.systemPermission(asked)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        asked = true
        permission = context.systemPermission(asked = true)
    }

    NotificationsScreen(
        enabled = enabled,
        permission = permission,
        onNavigateBack = onNavigateBack,
        onEnabledChange = viewModel::setEnabled,
        onGrant = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                request.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                context.openAppNotificationSettings()
            }
        },
        onOpenSystemSettings = { context.openAppNotificationSettings() },
        onOpenChannel = { context.openChannelSettings(it) },
    )
}

/**
 * Notifications.
 *
 * **Canon:** iOS `NotificationsSettingsView` — the app's switch, the system permission with a way
 * to fix it, message notifications, and how delivery works. Two deliberate differences:
 * - Sound and vibration open the message channel instead of being toggles. On Android 8+ they
 *   belong to the channel, and iOS's toggles are read by nothing.
 * - iOS's "Push notifications" section becomes "Delivery": there is no push provider here
 *   (AGENTS.md, no Google Play Services) — the app keeps its own connection, and the ongoing
 *   notification that comes with it is stated, with a way to hide it.
 */
@Composable
private fun NotificationsScreen(
    enabled: Boolean,
    permission: SystemPermission,
    onNavigateBack: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onGrant: () -> Unit,
    onOpenSystemSettings: () -> Unit,
    onOpenChannel: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CTColor.bg)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        CTNavBar(
            title = stringResource(R.string.notifications_title),
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
            CTSettingsSectionHeader(title = stringResource(R.string.notifications_title))
            CTSectionGroup {
                ToggleRow(
                    label = stringResource(R.string.notifications_enable),
                    checked = enabled,
                    onCheckedChange = onEnabledChange,
                )
            }
            Footer(stringResource(R.string.notifications_enable_footer))

            CTSettingsSectionHeader(title = stringResource(R.string.notifications_system_section))
            CTSectionGroup {
                CTSettingsRow(
                    label = stringResource(R.string.notifications_status),
                    value = stringResource(permission.label),
                    valueColor = permission.color,
                    status = permission.status,
                )
                when (permission) {
                    SystemPermission.ALLOWED -> Unit
                    SystemPermission.NOT_ASKED -> {
                        CTSep()
                        CTSettingsRow(
                            label = stringResource(R.string.notifications_grant),
                            labelColor = CTColor.accent,
                            disclosure = true,
                            modifier = Modifier.fillMaxWidth().clickable(onClick = onGrant),
                        )
                    }
                    SystemPermission.DENIED -> {
                        CTSep()
                        CTSettingsRow(
                            label = stringResource(R.string.notifications_open_system_settings),
                            disclosure = true,
                            modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenSystemSettings),
                        )
                    }
                }
            }
            if (permission == SystemPermission.DENIED) {
                Footer(stringResource(R.string.notifications_permission_required), color = CTColor.warning)
            } else {
                Footer(stringResource(R.string.notifications_system_footer))
            }

            if (enabled) {
                CTSettingsSectionHeader(title = stringResource(R.string.notifications_messages_section))
                CTSectionGroup {
                    CTSettingsRow(
                        label = stringResource(R.string.notifications_sound_vibration),
                        icon = Icons.Default.Tune,
                        disclosure = true,
                        modifier = Modifier.fillMaxWidth().clickable {
                            onOpenChannel(NotificationSettingsRepository.MESSAGES_CHANNEL)
                        },
                    )
                }
                Footer(stringResource(R.string.notifications_messages_footer))
            }

            CTSettingsSectionHeader(title = stringResource(R.string.notifications_delivery_section))
            CTSectionGroup {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = CTSpace.m, vertical = 10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = Icons.Default.Sensors,
                        contentDescription = null,
                        tint = CTColor.accent,
                        modifier = Modifier.padding(top = 2.dp).width(15.dp),
                    )
                    Spacer(Modifier.width(13.dp))
                    Text(
                        text = stringResource(R.string.notifications_delivery_text),
                        style = CTFont.secondary,
                        color = CTColor.textDim,
                    )
                }
                CTSep()
                CTSettingsRow(
                    label = stringResource(R.string.notifications_connection_notification),
                    icon = Icons.Default.NotificationsActive,
                    disclosure = true,
                    modifier = Modifier.fillMaxWidth().clickable {
                        onOpenChannel(NotificationSettingsRepository.CONNECTION_CHANNEL)
                    },
                )
            }
            Footer(stringResource(R.string.notifications_connection_footer))
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = CTSpace.m, vertical = CTSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // iOS sets these labels in textDim.
        Text(text = label, style = CTFont.body, color = CTColor.textDim, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = CTColor.bg,
                checkedTrackColor = CTColor.accent,
                uncheckedThumbColor = CTColor.textDim,
                uncheckedTrackColor = CTColor.outMsgBg,
                uncheckedBorderColor = CTColor.noise,
            ),
        )
    }
}

@Composable
private fun Footer(text: String, color: Color = CTColor.textDim) {
    Text(
        text = text,
        style = CTFont.caption,
        color = color,
        modifier = Modifier.padding(horizontal = CTLayout.edgePad * 2, vertical = CTSpace.s),
    )
}

private val SystemPermission.label: Int
    get() = when (this) {
        SystemPermission.ALLOWED -> R.string.notifications_status_allowed
        SystemPermission.NOT_ASKED -> R.string.notifications_status_not_set
        SystemPermission.DENIED -> R.string.notifications_status_denied
    }

private val SystemPermission.status: CTStatus
    get() = when (this) {
        SystemPermission.ALLOWED -> CTStatus.OK
        SystemPermission.NOT_ASKED -> CTStatus.WARNING
        SystemPermission.DENIED -> CTStatus.ERROR
    }

private val SystemPermission.color: Color
    get() = when (this) {
        SystemPermission.ALLOWED -> CTColor.accent
        SystemPermission.NOT_ASKED -> CTColor.warning
        SystemPermission.DENIED -> CTColor.danger
    }

/**
 * Android cannot say "never asked" directly. Before 13 there is no runtime permission, so it is
 * allowed or switched off in settings. From 13, a missing grant is offered as a request until one
 * has been made here and refused; after that the system would not show the dialog again anyway.
 */
private fun Context.systemPermission(asked: Boolean): SystemPermission {
    if (NotificationManagerCompat.from(this).areNotificationsEnabled()) return SystemPermission.ALLOWED
    val grantable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
    return if (grantable && !asked) SystemPermission.NOT_ASKED else SystemPermission.DENIED
}

private fun Context.openAppNotificationSettings() {
    startActivity(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

private fun Context.openChannelSettings(channelId: String) {
    startActivity(
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

@Preview(backgroundColor = 0xFF090909, showBackground = true, heightDp = 900, widthDp = 360)
@Composable
private fun NotificationsScreenPreview() {
    NotificationsScreen(
        enabled = true,
        permission = SystemPermission.NOT_ASKED,
        onNavigateBack = {},
        onEnabledChange = {},
        onGrant = {},
        onOpenSystemSettings = {},
        onOpenChannel = {},
    )
}
