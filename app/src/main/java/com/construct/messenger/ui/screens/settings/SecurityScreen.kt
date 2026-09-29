package com.construct.messenger.ui.screens.settings

import android.text.format.DateFormat
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
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import com.construct.messenger.data.repository.AppLockState
import com.construct.messenger.data.repository.LockDelay
import com.construct.messenger.ui.screens.security.PinFlow
import com.construct.messenger.ui.theme.ctBold
import com.construct.messenger.viewmodel.AppLockViewModel
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PendingActions
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.construct.messenger.R
import com.construct.messenger.data.repository.Lockdown
import com.construct.messenger.recovery.RecoveryStatus
import com.construct.messenger.ui.components.CTConfirmDialog
import com.construct.messenger.ui.components.CTNavBar
import com.construct.messenger.ui.components.CTSep
import com.construct.messenger.ui.components.CTSettingsRow
import com.construct.messenger.ui.components.CTStatus
import com.construct.messenger.ui.components.CTStatusBadge
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTLayout
import com.construct.messenger.ui.theme.ctRegular
import com.construct.messenger.viewmodel.SecurityUiState
import com.construct.messenger.viewmodel.SecurityViewModel
import java.util.Date

@Composable
fun SecurityScreen(
    onNavigateBack: () -> Unit,
    onRecovery: () -> Unit,
    onIssuedInvites: () -> Unit,
    onPin: (PinFlow) -> Unit,
    viewModel: SecurityViewModel = hiltViewModel(),
    lockViewModel: AppLockViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val lock by lockViewModel.lock.collectAsStateWithLifecycle()
    var pickDelay by remember { mutableStateOf(false) }
    if (pickDelay) {
        LockDelayDialog(
            current = lock.lockDelay,
            onPick = { pickDelay = false; lockViewModel.setLockDelay(it) },
            onDismiss = { pickDelay = false },
        )
    }
    // On every entry: the phrase may have just been set up on the screen this returns from.
    LaunchedEffect(Unit) { viewModel.refreshRecovery() }
    var confirmEnable by remember { mutableStateOf(false) }
    if (confirmEnable) {
        CTConfirmDialog(
            title = stringResource(R.string.searchable_confirm_title),
            message = stringResource(R.string.searchable_confirm_message),
            confirmLabel = stringResource(R.string.searchable_confirm_action),
            dismissLabel = stringResource(R.string.action_cancel),
            onConfirm = {
                confirmEnable = false
                viewModel.setDiscoverable(true)
            },
            onDismiss = { confirmEnable = false },
        )
    }
    SecurityContent(
        ui = ui,
        lock = lock,
        onPin = onPin,
        onBiometric = lockViewModel::setBiometricEnabled,
        onLockDelay = { pickDelay = true },
        onNavigateBack = onNavigateBack,
        onRecovery = onRecovery,
        onLockdown = viewModel::setLockdown,
        onIssuedInvites = onIssuedInvites,
        // On is asked about first; off is not — being harder to find needs no warning.
        onDiscoverable = { enabled -> if (enabled) confirmEnable = true else viewModel.setDiscoverable(false) },
    )
}

/**
 * Security.
 *
 * **Canon:** iOS `SecurityView` — one flat list, blocks divided by a separator, each with its
 * hint beneath. Order: PIN (biometrics, lock delay, off), recovery phrase, Lockdown, sender
 * anonymity, issued invites, discovery. Not ported: the duress PIN (a separate step — it erases
 * the account); key transparency, which Android does not have.
 */
@Composable
private fun SecurityContent(
    ui: SecurityUiState,
    lock: AppLockState,
    onPin: (PinFlow) -> Unit,
    onBiometric: (Boolean) -> Unit,
    onLockDelay: () -> Unit,
    onNavigateBack: () -> Unit,
    onRecovery: () -> Unit,
    onLockdown: (Boolean) -> Unit,
    onIssuedInvites: () -> Unit,
    onDiscoverable: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CTColor.bg)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        CTNavBar(
            title = stringResource(R.string.security_title),
            showBack = true,
            onBack = onNavigateBack,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            PinBlock(lock = lock, onPin = onPin, onBiometric = onBiometric, onLockDelay = onLockDelay)
            CTSep()

            RecoveryRow(recovery = ui.recovery, onClick = onRecovery)
            Hint(stringResource(R.string.security_recovery_hint))
            CTSep()

            LockdownRow(lockdown = ui.lockdown, onChange = onLockdown)
            Hint(stringResource(R.string.security_lockdown_hint))
            CTSep()

            // A statement, not a setting: sealed sender is always on (iOS stealth Phase 4). Read
            // rather than assumed, so a debug override shows honestly.
            SecurityRow(vertical = CTLayout.chromeGap) {
                RowIcon(
                    Icons.Default.VisibilityOff,
                    if (ui.senderAnonymity) CTColor.accent else CTColor.danger,
                )
                Text(
                    text = stringResource(R.string.security_stealth_title),
                    style = ctRegular(13),
                    color = CTColor.text,
                    modifier = Modifier.weight(1f),
                )
                CTStatusBadge(status = if (ui.senderAnonymity) CTStatus.ON else CTStatus.ERROR, size = 11.dp)
            }
            Hint(
                text = stringResource(
                    if (ui.senderAnonymity) R.string.security_stealth_on_hint else R.string.security_stealth_off_hint,
                ),
                color = if (ui.senderAnonymity) CTColor.textDim else CTColor.danger,
                top = 2.dp,
            )
            CTSep()

            CTSettingsRow(
                label = stringResource(R.string.issued_invites_title).uppercase(),
                icon = Icons.Default.PendingActions,
                disclosure = true,
                modifier = Modifier.fillMaxWidth().clickable(onClick = onIssuedInvites),
            )
            CTSep()

            DiscoveryRow(ui = ui, onChange = onDiscoverable)
            Hint(
                text = when {
                    ui.failed -> stringResource(R.string.searchable_failed)
                    ui.hasUsername -> stringResource(R.string.searchable_toggle_footer)
                    else -> stringResource(R.string.searchable_no_username_hint)
                },
                color = if (ui.failed) CTColor.danger else CTColor.textDim,
            )
        }
    }
}

@Composable
private fun PinBlock(
    lock: AppLockState,
    onPin: (PinFlow) -> Unit,
    onBiometric: (Boolean) -> Unit,
    onLockDelay: () -> Unit,
) {
    SecurityRow(modifier = Modifier.clickable { onPin(if (lock.pinEnabled) PinFlow.CHANGE else PinFlow.CREATE) }) {
        Text(
            text = stringResource(if (lock.pinEnabled) R.string.pin_change else R.string.pin_enable),
            style = ctRegular(13),
            color = CTColor.text,
            modifier = Modifier.weight(1f),
        )
        Chevron()
    }
    if (!lock.pinEnabled) return
    CTSep()
    SecurityRow(vertical = CTLayout.chromeGap) {
        RowIcon(Icons.Default.Fingerprint, if (lock.biometricEnabled) CTColor.accent else CTColor.textDim)
        Text(
            text = stringResource(R.string.security_use_biometric),
            style = ctRegular(13),
            color = if (lock.biometricAvailable) CTColor.text else CTColor.textDim,
            modifier = Modifier.weight(1f),
        )
        CTSwitch(checked = lock.biometricEnabled, onCheckedChange = onBiometric, enabled = lock.biometricAvailable)
    }
    CTSep()
    SecurityRow(modifier = Modifier.clickable(onClick = onLockDelay)) {
        RowIcon(Icons.Default.Timer, CTColor.textDim)
        Text(
            text = stringResource(R.string.lock_delay),
            style = ctRegular(13),
            color = CTColor.text,
            modifier = Modifier.weight(1f),
        )
        Text(text = stringResource(lock.lockDelay.label), style = ctRegular(12), color = CTColor.textDim)
        Chevron()
    }
    CTSep()
    SecurityRow(modifier = Modifier.clickable { onPin(PinFlow.DISABLE) }) {
        RowIcon(Icons.Default.Cancel, CTColor.danger)
        Text(text = stringResource(R.string.pin_disable), style = ctRegular(13), color = CTColor.danger)
    }
}

/** iOS `confirmationDialog` for the lock delay: the choices, the current one checked. */
@Composable
private fun LockDelayDialog(current: LockDelay, onPick: (LockDelay) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CTColor.outMsgBg,
        title = { Text(stringResource(R.string.lock_delay), style = ctBold(15), color = CTColor.text) },
        text = {
            Column {
                LockDelay.entries.forEach { delay ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(delay) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(delay.label),
                            style = ctRegular(14),
                            color = CTColor.text,
                            modifier = Modifier.weight(1f),
                        )
                        if (delay == current) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = CTColor.accent, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel), style = ctRegular(13), color = CTColor.textDim)
            }
        },
    )
}

private val LockDelay.label: Int
    get() = when (this) {
        LockDelay.IMMEDIATE -> R.string.lock_delay_immediate
        LockDelay.THIRTY_SECONDS -> R.string.lock_delay_30s
        LockDelay.ONE_MINUTE -> R.string.lock_delay_1m
        LockDelay.FIVE_MINUTES -> R.string.lock_delay_5m
        LockDelay.TEN_MINUTES -> R.string.lock_delay_10m
    }

@Composable
private fun RecoveryRow(recovery: RecoveryStatus?, onClick: () -> Unit) {
    SecurityRow(modifier = Modifier.clickable(onClick = onClick)) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = stringResource(R.string.security_recovery_title), style = ctRegular(13), color = CTColor.text)
            when {
                recovery?.isSetup == true && recovery.fingerprint != null -> Text(
                    text = recovery.fingerprint,
                    style = ctRegular(11),
                    color = CTColor.accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                recovery?.isSetup == false -> Text(
                    text = stringResource(R.string.security_recovery_not_set_up),
                    style = ctRegular(11),
                    color = CTColor.warning,
                )
            }
        }
        Chevron()
    }
}

@Composable
private fun LockdownRow(lockdown: Lockdown, onChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    SecurityRow(vertical = CTLayout.chromeGap) {
        RowIcon(
            if (lockdown.isActive) Icons.Default.LockOpen else Icons.Default.Lock,
            if (lockdown.isActive) CTColor.warning else CTColor.textDim,
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = stringResource(R.string.security_lockdown_title), style = ctRegular(13), color = CTColor.text)
            lockdown.activatedAt?.let { since ->
                val date = Date(since)
                val formatted = DateFormat.getMediumDateFormat(context).format(date) + " " +
                    DateFormat.getTimeFormat(context).format(date)
                Text(
                    text = stringResource(R.string.security_lockdown_since, formatted),
                    style = ctRegular(11),
                    color = CTColor.warning,
                )
            }
        }
        CTSwitch(checked = lockdown.isActive, onCheckedChange = onChange)
    }
}

@Composable
private fun DiscoveryRow(ui: SecurityUiState, onChange: (Boolean) -> Unit) {
    SecurityRow(vertical = CTLayout.chromeGap) {
        RowIcon(
            if (ui.discoverable) Icons.Default.Visibility else Icons.Default.VisibilityOff,
            if (ui.discoverable) CTColor.accent else CTColor.textDim,
        )
        Text(
            text = stringResource(R.string.searchable_toggle_title),
            style = ctRegular(13),
            color = if (ui.hasUsername) CTColor.text else CTColor.textDim,
            modifier = Modifier.weight(1f),
        )
        if (ui.busy) {
            CircularProgressIndicator(
                color = CTColor.accent,
                strokeWidth = 2.dp,
                modifier = Modifier
                    .padding(12.dp)
                    .size(20.dp),
            )
        } else {
            // Switching off stays possible without an alias; switching on does not.
            CTSwitch(checked = ui.discoverable, onCheckedChange = onChange, enabled = ui.hasUsername || ui.discoverable)
        }
    }
}

/** iOS `securityRowInsets`: edgePad horizontally, [vertical] (edgePad, or chromeGap for a switch). */
@Composable
private fun SecurityRow(
    modifier: Modifier = Modifier,
    vertical: Dp = CTLayout.edgePad,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = CTLayout.edgePad, vertical = vertical),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CTLayout.chromeGap),
        content = content,
    )
}

/** iOS `CTRowIcon(sf:)`: a 16pt symbol, which draws a little larger than its point size. */
@Composable
private fun RowIcon(icon: ImageVector, tint: Color) {
    Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
}

@Composable
private fun Chevron() {
    Icon(
        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = CTColor.textDim,
        modifier = Modifier.size(16.dp),
    )
}

@Composable
private fun CTSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = CTColor.bg,
            checkedTrackColor = CTColor.accent,
            uncheckedThumbColor = CTColor.textDim,
            uncheckedTrackColor = CTColor.outMsgBg,
            uncheckedBorderColor = CTColor.noise,
        ),
    )
}

/** iOS `securityHintText`: caption, edgePad sides, 6 above (2 when compact), chromeGap below. */
@Composable
private fun Hint(text: String, color: Color = CTColor.textDim, top: Dp = 6.dp) {
    Text(
        text = text,
        style = ctRegular(11),
        color = color,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = CTLayout.edgePad)
            .padding(top = top, bottom = CTLayout.chromeGap),
    )
}

@Preview(backgroundColor = 0xFF090909, showBackground = true, heightDp = 800, widthDp = 360)
@Composable
private fun SecurityContentPreview() {
    SecurityContent(
        lock = AppLockState(pinEnabled = true, biometricAvailable = true),
        onPin = {},
        onBiometric = {},
        onLockDelay = {},
        ui = SecurityUiState(
            hasUsername = true,
            recovery = RecoveryStatus(isSetup = true, fingerprint = "a1b2 c3d4 e5f6 0718 9a0b"),
            lockdown = Lockdown(activatedAt = 1_759_100_000_000),
        ),
        onNavigateBack = {},
        onRecovery = {},
        onLockdown = {},
        onIssuedInvites = {},
        onDiscoverable = {},
    )
}
