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
import com.construct.messenger.ui.components.CTSepStyle
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
    viewModel: SecurityViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
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
 * hint beneath. Order: recovery phrase, Lockdown, sender anonymity, issued invites, discovery.
 * Not ported yet: PIN / biometric lock and the duress PIN (next steps of plan B7); key
 * transparency, which Android does not have.
 */
@Composable
private fun SecurityContent(
    ui: SecurityUiState,
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
            RecoveryRow(recovery = ui.recovery, onClick = onRecovery)
            Hint(stringResource(R.string.security_recovery_hint))
            CTSep(style = CTSepStyle.THICK)

            LockdownRow(lockdown = ui.lockdown, onChange = onLockdown)
            Hint(stringResource(R.string.security_lockdown_hint))
            CTSep(style = CTSepStyle.THICK)

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
            CTSep(style = CTSepStyle.THICK)

            CTSettingsRow(
                label = stringResource(R.string.issued_invites_title).uppercase(),
                icon = Icons.Default.PendingActions,
                disclosure = true,
                modifier = Modifier.fillMaxWidth().clickable(onClick = onIssuedInvites),
            )
            CTSep(style = CTSepStyle.THICK)

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
                    color = CTColor.danger,
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

/** iOS `CTRowIcon`: 14pt. */
@Composable
private fun RowIcon(icon: ImageVector, tint: Color) {
    Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(15.dp))
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
