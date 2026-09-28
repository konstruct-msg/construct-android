package com.construct.messenger.ui.screens.synaps

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.construct.messenger.R
import com.construct.messenger.ui.components.CTConfirmDialog
import com.construct.messenger.ui.components.CTNavBar
import com.construct.messenger.ui.components.CTSectionGroup
import com.construct.messenger.ui.components.CTSep
import com.construct.messenger.ui.components.CTSettingsRow
import com.construct.messenger.ui.components.CTSettingsSectionHeader
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.Spacing
import com.construct.messenger.ui.theme.ctBold
import com.construct.messenger.ui.theme.ctRegular
import com.construct.messenger.viewmodel.ContactProfileViewModel

private enum class Confirm { BLOCK, UNBLOCK, REPORT, DELETE }

/**
 * A contact: who they are, and what can be done to them. **Canon:** iOS `UserProfileView` —
 * identity, open chat / safety numbers, then the danger zone (block, report, remove).
 */
@Composable
fun ContactProfileScreen(
    onNavigateBack: () -> Unit,
    onOpenChat: (String) -> Unit,
    onOpenSafetyNumbers: (String) -> Unit,
    viewModel: ContactProfileViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf<Confirm?>(null) }

    LaunchedEffect(ui.removed) {
        if (ui.removed) onNavigateBack()
    }

    confirm?.let { which ->
        val (title, message, action) = when (which) {
            Confirm.BLOCK -> Triple(R.string.contact_block_title, R.string.contact_block_message, R.string.contact_block)
            Confirm.UNBLOCK -> Triple(R.string.contact_unblock_title, R.string.contact_unblock_message, R.string.contact_unblock)
            Confirm.REPORT -> Triple(R.string.contact_report_title, R.string.contact_report_message, R.string.contact_report)
            Confirm.DELETE -> Triple(R.string.contact_delete_title, R.string.contact_delete_message, R.string.contact_delete)
        }
        CTConfirmDialog(
            title = stringResource(title),
            message = stringResource(message, ui.name),
            confirmLabel = stringResource(action),
            dismissLabel = stringResource(R.string.action_cancel),
            isDestructive = which != Confirm.UNBLOCK,
            onConfirm = {
                confirm = null
                when (which) {
                    Confirm.BLOCK -> viewModel.setBlocked(true)
                    Confirm.UNBLOCK -> viewModel.setBlocked(false)
                    Confirm.REPORT -> viewModel.reportSpam()
                    Confirm.DELETE -> viewModel.delete()
                }
            },
            onDismiss = { confirm = null },
        )
    }

    ui.reportAccepted?.let { accepted ->
        AlertDialog(
            onDismissRequest = viewModel::reportShown,
            containerColor = CTColor.outMsgBg,
            text = {
                Text(
                    stringResource(if (accepted) R.string.contact_report_sent else R.string.contact_report_failed),
                    style = ctRegular(13),
                    color = CTColor.textDim,
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::reportShown) {
                    Text(stringResource(R.string.close), style = ctBold(13), color = CTColor.accent)
                }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CTColor.bg)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        CTNavBar(title = ui.name, showBack = true, onBack = onNavigateBack)
        CTSep()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(vertical = Spacing.medium),
            verticalArrangement = Arrangement.spacedBy(Spacing.medium),
        ) {
            CTSectionGroup {
                if (ui.username.isNotBlank()) {
                    CTSettingsRow(label = stringResource(R.string.contact_username), value = "@${ui.username}")
                    CTSep()
                }
                ui.fingerprint?.let {
                    CTSettingsRow(label = stringResource(R.string.contact_fingerprint), value = it)
                    CTSep()
                }
                CTSettingsRow(
                    label = stringResource(R.string.contact_address),
                    value = ui.address?.let { "${it.take(18)}…" } ?: stringResource(R.string.contact_address_unknown),
                    valueColor = CTColor.textDim,
                )
            }

            CTSectionGroup {
                CTSettingsRow(
                    label = stringResource(R.string.contact_open_chat),
                    icon = Icons.AutoMirrored.Filled.Chat,
                    disclosure = true,
                    modifier = Modifier.clickable { onOpenChat(viewModel.userId) },
                )
                CTSep()
                CTSettingsRow(
                    label = stringResource(R.string.safety_numbers),
                    icon = Icons.Default.Shield,
                    disclosure = true,
                    modifier = Modifier.clickable { onOpenSafetyNumbers(viewModel.userId) },
                )
            }

            CTSettingsSectionHeader(title = stringResource(R.string.contact_danger_zone), color = CTColor.danger)
            CTSectionGroup {
                CTSettingsRow(
                    label = stringResource(if (ui.isBlocked) R.string.contact_unblock else R.string.contact_block),
                    icon = Icons.Default.Block,
                    isDestructive = !ui.isBlocked,
                    modifier = Modifier.clickable(enabled = !ui.busy) {
                        confirm = if (ui.isBlocked) Confirm.UNBLOCK else Confirm.BLOCK
                    },
                )
                CTSep()
                CTSettingsRow(
                    label = stringResource(R.string.contact_report),
                    icon = Icons.Default.Flag,
                    isDestructive = true,
                    modifier = Modifier.clickable(enabled = !ui.busy) { confirm = Confirm.REPORT },
                )
                CTSep()
                CTSettingsRow(
                    label = stringResource(R.string.contact_delete),
                    icon = Icons.Default.Delete,
                    isDestructive = true,
                    modifier = Modifier.clickable(enabled = !ui.busy) { confirm = Confirm.DELETE },
                )
            }
        }
    }
}
