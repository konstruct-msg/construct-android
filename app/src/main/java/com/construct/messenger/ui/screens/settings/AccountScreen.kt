package com.construct.messenger.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.construct.messenger.R
import com.construct.messenger.ui.components.CTAvatar
import com.construct.messenger.ui.components.CTConfirmDialog
import com.construct.messenger.ui.components.CTNavBar
import com.construct.messenger.ui.components.CTStatus
import com.construct.messenger.ui.components.CTStatusBadge
import com.construct.messenger.ui.components.CTTextField
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTLayout
import com.construct.messenger.ui.theme.ctBold
import com.construct.messenger.ui.theme.ctRegular
import com.construct.messenger.viewmodel.AccountEvent
import com.construct.messenger.viewmodel.AccountUiState
import com.construct.messenger.viewmodel.AccountViewModel
import com.construct.messenger.viewmodel.UsernameError
import kotlinx.coroutines.delay

/**
 * Who this account is: alias, fingerprint, account id — and signing out.
 *
 * **Canon:** iOS `AccountSettingsView` — flat sections with `> HEADER`, lowercase dim labels,
 * values on the right; "edit" turns the alias into a field. Not ported: photo, editable
 * display name (iOS re-sends the profile to contacts; Android has no such path yet), linked
 * devices, recovery, backup, danger zone.
 */
@Composable
fun AccountScreen(
    onNavigateBack: () -> Unit,
    onSignedOut: () -> Unit,
    viewModel: AccountViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.eventsFlow.collect { if (it is AccountEvent.SignedOut) onSignedOut() }
    }
    // Back while editing abandons the edit, as the nav bar's back does.
    BackHandler(enabled = ui.editing) { viewModel.cancelEditing() }

    var confirmSignOut by remember { mutableStateOf(false) }
    if (confirmSignOut) {
        CTConfirmDialog(
            title = stringResource(R.string.logout_confirm_title),
            message = stringResource(R.string.logout_confirm_message),
            confirmLabel = stringResource(R.string.logout_confirm_action),
            dismissLabel = stringResource(R.string.action_cancel),
            isDestructive = true,
            onConfirm = {
                confirmSignOut = false
                viewModel.signOut()
            },
            onDismiss = { confirmSignOut = false },
        )
    }

    AccountContent(
        ui = ui,
        onBack = { if (ui.editing) viewModel.cancelEditing() else onNavigateBack() },
        onEdit = viewModel::startEditing,
        onSave = viewModel::save,
        onDraftChange = viewModel::onDraftChange,
        onSignOut = { confirmSignOut = true },
    )
}

@Composable
private fun AccountContent(
    ui: AccountUiState,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onSave: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSignOut: () -> Unit,
) {
    val account = ui.account
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CTColor.bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        CTNavBar(
            title = stringResource(if (ui.editing) R.string.account_editing_title else R.string.account_title),
            showBack = true,
            onBack = onBack,
            trailingIcon = if (ui.editing) Icons.Default.Check else Icons.Default.Edit,
            trailingColor = if (ui.saving) CTColor.textDim else CTColor.accent,
            onTrailingAction = { if (ui.editing) onSave() else onEdit() },
        )
        Divider(thick = true)

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                CTAvatar(
                    userId = account?.userId.orEmpty(),
                    displayName = account?.displayName.orEmpty(),
                    size = 88.dp,
                )
            }
            Divider(thick = true)

            SectionHeader(stringResource(R.string.account_section_identity))
            RowDivider()
            UsernameRow(ui = ui, onDraftChange = onDraftChange)
            RowDivider()
            SearchableRow(searchable = account?.discoverable == true)
            RowDivider()
            InfoRow(label = stringResource(R.string.account_display_name)) {
                Text(account?.displayName.orEmpty(), style = ctRegular(14), color = CTColor.text)
            }
            RowDivider()
            CopyRow(
                label = stringResource(R.string.identity_fingerprint),
                shown = account?.fingerprint,
                copied = account?.fingerprint,
                unknown = stringResource(R.string.identity_fingerprint_unknown),
                copiedLabel = stringResource(R.string.identity_fingerprint_copied),
            )
            Divider(thick = true)

            // Out of reach while editing, as on iOS: one change at a time.
            Column(modifier = Modifier.alpha(if (ui.editing) 0.4f else 1f)) {
                SectionHeader(stringResource(R.string.account_section_account))
                RowDivider()
                CopyRow(
                    label = stringResource(R.string.account_user_id),
                    shown = account?.userId?.let(::shortId),
                    copied = account?.userId,
                    unknown = "",
                    copiedLabel = stringResource(R.string.account_user_id_copied),
                    enabled = !ui.editing,
                )
                RowDivider()
                InfoRow(
                    label = stringResource(R.string.account_sign_out),
                    onClick = onSignOut.takeUnless { ui.editing || ui.signingOut },
                ) { Chevron() }
            }
            Divider(thick = true)

            Text(
                text = stringResource(R.string.changes_encrypted_footer),
                style = ctRegular(11),
                color = CTColor.accent.copy(alpha = 0.6f),
                modifier = Modifier.padding(horizontal = ROW_H_PAD, vertical = 16.dp),
            )
        }
    }
}

@Composable
private fun UsernameRow(ui: AccountUiState, onDraftChange: (String) -> Unit) {
    val username = ui.account?.username.orEmpty()
    Column(modifier = Modifier.padding(horizontal = ROW_H_PAD, vertical = ROW_V_PAD)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.account_username),
                style = ctRegular(14),
                color = CTColor.textDim,
                modifier = Modifier.weight(1f),
            )
            if (!ui.editing) {
                Text(
                    text = if (username.isEmpty()) stringResource(R.string.username_not_set) else "@$username",
                    style = ctRegular(14),
                    color = if (username.isEmpty()) CTColor.textDim else CTColor.accent,
                )
            }
        }
        if (ui.editing) {
            // "Edit" means "type now": the field takes focus and the keyboard comes up.
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { focus.requestFocus() }
            Spacer(Modifier.height(8.dp))
            CTTextField(
                placeholder = stringResource(R.string.account_username),
                value = ui.draftUsername,
                onValueChange = onDraftChange,
                modifier = Modifier.focusRequester(focus),
            )
        }
        val error = ui.usernameError
        if (error != null || !ui.editing) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (error != null) stringResource(error.message()) else stringResource(R.string.account_username_hint),
                style = ctRegular(11),
                color = if (error != null) CTColor.danger else CTColor.textDim,
            )
        }
    }
}

@Composable
private fun SearchableRow(searchable: Boolean) {
    Row(
        modifier = Modifier.padding(horizontal = ROW_H_PAD, vertical = ROW_V_PAD),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CTStatusBadge(status = if (searchable) CTStatus.ON else CTStatus.OFF, size = 12.dp)
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(if (searchable) R.string.searchable_indicator else R.string.searchable_indicator_off),
            style = ctRegular(12),
            color = if (searchable) CTColor.accent else CTColor.textDim,
        )
    }
}

/** Tap to copy [copied]; the value reads [copiedLabel] for a moment to say it worked. */
@Composable
private fun CopyRow(
    label: String,
    shown: String?,
    copied: String?,
    unknown: String,
    copiedLabel: String,
    enabled: Boolean = true,
) {
    val clipboard = LocalClipboardManager.current
    var flash by remember { mutableStateOf(false) }
    LaunchedEffect(flash) {
        if (flash) {
            delay(COPIED_FLASH_MS)
            flash = false
        }
    }
    InfoRow(
        label = label,
        onClick = if (enabled && copied != null) {
            {
                clipboard.setText(AnnotatedString(copied))
                flash = true
            }
        } else {
            null
        },
    ) {
        Text(
            text = when {
                shown == null -> unknown
                flash -> copiedLabel
                else -> shown
            },
            style = ctRegular(13),
            color = if (shown == null) CTColor.textDim else CTColor.accent,
            maxLines = 1,
        )
    }
}

@Composable
private fun InfoRow(
    label: String,
    onClick: (() -> Unit)? = null,
    value: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = ROW_H_PAD, vertical = ROW_V_PAD),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label.lowercase(),
            style = ctRegular(14),
            color = CTColor.textDim,
            modifier = Modifier.weight(1f),
        )
        value()
    }
}

@Composable
private fun Chevron() {
    Icon(
        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = CTColor.accent,
        modifier = Modifier.size(18.dp),
    )
}

@Composable
private fun SectionHeader(title: String, color: Color = CTColor.accent) {
    Row(modifier = Modifier.padding(horizontal = ROW_H_PAD, vertical = 10.dp)) {
        Text(">", style = ctBold(12), color = color)
        Spacer(Modifier.width(6.dp))
        Text(title.uppercase(), style = ctBold(12), color = color)
    }
}

@Composable
private fun Divider(thick: Boolean = false) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (thick) 1.dp else 0.5.dp)
            .background(CTColor.noise),
    )
}

@Composable
private fun RowDivider() {
    Box(
        modifier = Modifier
            .padding(horizontal = ROW_H_PAD)
            .fillMaxWidth()
            .height(0.5.dp)
            .background(CTColor.noise.copy(alpha = 0.5f)),
    )
}

private fun UsernameError.message(): Int = when (this) {
    UsernameError.LENGTH -> R.string.username_length_error
    UsernameError.UNAVAILABLE -> R.string.username_unavailable
    UsernameError.FAILED -> R.string.username_save_failed
}

/** iOS: `8 chars…last 2` — enough to tell accounts apart, the full id is one tap away. */
private fun shortId(id: String): String = if (id.length > 12) "${id.take(8)}…${id.takeLast(2)}" else id

private val ROW_H_PAD = CTLayout.edgePad + 4.dp
private val ROW_V_PAD = 12.dp
private const val COPIED_FLASH_MS = 1_500L
