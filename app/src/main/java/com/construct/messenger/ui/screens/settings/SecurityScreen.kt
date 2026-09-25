package com.construct.messenger.ui.screens.settings

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.construct.messenger.R
import com.construct.messenger.ui.components.CTConfirmDialog
import com.construct.messenger.ui.components.CTNavBar
import com.construct.messenger.ui.components.CTSectionGroup
import com.construct.messenger.ui.components.CTSettingsSectionHeader
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTLayout
import com.construct.messenger.ui.theme.ctRegular
import com.construct.messenger.viewmodel.SecurityUiState
import com.construct.messenger.viewmodel.SecurityViewModel

/**
 * Security settings. Today only Discovery — whether the exact alias finds this account.
 *
 * **Canon:** iOS `SecurityView` → Discovery row, footer and confirmation. Not ported yet: PIN /
 * biometric lock, lockdown, issued invites, key transparency.
 */
@Composable
fun SecurityScreen(
    onNavigateBack: () -> Unit,
    viewModel: SecurityViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
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
            CTSettingsSectionHeader(title = stringResource(R.string.security_section_discovery))
            CTSectionGroup {
                DiscoveryRow(
                    ui = ui,
                    // On is asked about first; off is not — being harder to find needs no warning.
                    onChange = { enabled -> if (enabled) confirmEnable = true else viewModel.setDiscoverable(false) },
                )
            }
            Text(
                text = when {
                    ui.failed -> stringResource(R.string.searchable_failed)
                    ui.hasUsername -> stringResource(R.string.searchable_toggle_footer)
                    else -> stringResource(R.string.searchable_no_username_hint)
                },
                style = ctRegular(11),
                color = if (ui.failed) CTColor.danger else CTColor.textDim,
                modifier = Modifier.padding(horizontal = CTLayout.edgePad * 2, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun DiscoveryRow(ui: SecurityUiState, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (ui.discoverable) Icons.Default.Visibility else Icons.Default.VisibilityOff,
            contentDescription = null,
            tint = if (ui.discoverable) CTColor.accent else CTColor.textDim,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(13.dp))
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
            Switch(
                checked = ui.discoverable,
                onCheckedChange = onChange,
                // Switching off stays possible without an alias; switching on does not.
                enabled = ui.hasUsername || ui.discoverable,
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
}
