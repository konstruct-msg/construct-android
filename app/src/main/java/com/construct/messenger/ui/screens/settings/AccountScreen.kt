package com.construct.messenger.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.construct.messenger.R
import com.construct.messenger.ui.components.AvatarCropDialog
import com.construct.messenger.ui.components.AvatarViewerDialog
import com.construct.messenger.ui.components.CTAvatar
import com.construct.messenger.ui.components.CTConfirmDialog
import com.construct.messenger.ui.components.CTNavBar
import com.construct.messenger.ui.components.CTStatus
import com.construct.messenger.ui.components.CTStatusBadge
import com.construct.messenger.ui.components.CTTextField
import com.construct.messenger.ui.components.rememberAvatar
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTFont
import com.construct.messenger.ui.theme.CTIcon
import com.construct.messenger.ui.theme.CTLayout
import com.construct.messenger.viewmodel.AccountEvent
import com.construct.messenger.viewmodel.AccountUiState
import com.construct.messenger.viewmodel.AccountViewModel
import com.construct.messenger.viewmodel.UsernameError
import kotlinx.coroutines.delay

/**
 * Who this account is: alias, fingerprint, account id — and signing out.
 *
 * **Canon:** iOS `AccountSettingsView` — flat sections with `> HEADER`, lowercase dim labels,
 * values on the right; "edit" turns the alias into a field; sign-out warns first when the
 * recovery phrase is not set up; the danger zone signs out everywhere or deletes the account.
 * The avatar is iOS's too: tapped, it opens (or, with none, the picker does); "change photo"
 * picks, crops square and keeps it, then sends the profile again to whoever it is shared with.
 * Not ported: an editable display name, backup and nearby transfer, social recovery, and iOS's
 * "status" row, which is a placeholder there.
 */
@Composable
fun AccountScreen(
    onNavigateBack: () -> Unit,
    onSignedOut: () -> Unit,
    onDevices: () -> Unit,
    onRecoverySetup: () -> Unit,
    viewModel: AccountViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.eventsFlow.collect { if (it is AccountEvent.SignedOut) onSignedOut() }
    }
    // Back while editing abandons the edit, as the nav bar's back does.
    BackHandler(enabled = ui.editing) { viewModel.cancelEditing() }

    // Avatar: picked → cropped → kept. iOS `AccountSettingsView` photosPicker + ImageCropView.
    var toCrop by remember { mutableStateOf<android.net.Uri?>(null) }
    var viewingAvatar by remember { mutableStateOf(false) }
    val pickAvatar = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) toCrop = uri
    }
    val changeAvatar = { pickAvatar.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    toCrop?.let { uri ->
        AvatarCropDialog(
            uri = uri,
            onConfirm = { toCrop = null; viewModel.setAvatar(it) },
            onCancel = { toCrop = null },
        )
    }
    val avatar = ui.account?.avatar
    if (viewingAvatar && avatar != null) {
        AvatarViewerDialog(
            jpeg = avatar,
            onChange = { viewingAvatar = false; changeAvatar() },
            onDismiss = { viewingAvatar = false },
        )
    }

    var showDelete by remember { mutableStateOf(false) }
    if (showDelete) {
        DeleteAccountSheet(
            deletion = ui.deletion,
            onDelete = viewModel::startDeletion,
            onAbort = viewModel::abortDeletion,
            onDeleteLocally = viewModel::deleteLocally,
            onDismiss = { viewModel.abortDeletion(); showDelete = false },
        )
    }
    // Which sign-out was asked for; the no-backup warning comes first when the phrase is missing.
    var confirmSignOut by remember { mutableStateOf<Boolean?>(null) }
    var noBackupFor by remember { mutableStateOf<Boolean?>(null) }
    val askSignOut = { allDevices: Boolean ->
        if (ui.recoveryMissing) noBackupFor = allDevices else confirmSignOut = allDevices
    }
    noBackupFor?.let { allDevices ->
        NoBackupDialog(
            onSetUp = { noBackupFor = null; onRecoverySetup() },
            onProceed = { noBackupFor = null; confirmSignOut = allDevices },
            onDismiss = { noBackupFor = null },
        )
    }
    confirmSignOut?.let { allDevices ->
        CTConfirmDialog(
            title = stringResource(if (allDevices) R.string.logout_all_confirm_title else R.string.logout_confirm_title),
            message = stringResource(if (allDevices) R.string.logout_all_confirm_message else R.string.logout_confirm_message),
            confirmLabel = stringResource(if (allDevices) R.string.logout_all_confirm_action else R.string.logout_confirm_action),
            dismissLabel = stringResource(R.string.action_cancel),
            isDestructive = true,
            onConfirm = {
                confirmSignOut = null
                viewModel.signOut(allDevices)
            },
            onDismiss = { confirmSignOut = null },
        )
    }

    AccountContent(
        ui = ui,
        onBack = { if (ui.editing) viewModel.cancelEditing() else onNavigateBack() },
        onEdit = viewModel::startEditing,
        onSave = viewModel::save,
        onDraftChange = viewModel::onDraftChange,
        onDisplayNameDraftChange = viewModel::onDisplayNameDraftChange,
        onSignOut = { askSignOut(false) },
        onSignOutAll = { askSignOut(true) },
        onDevices = onDevices,
        onDeleteAccount = { showDelete = true },
        onAvatar = { if (avatar != null) viewingAvatar = true else changeAvatar() },
        onChangeAvatar = changeAvatar,
        onRemoveAvatar = viewModel::removeAvatar,
    )
}

@Composable
private fun AccountContent(
    ui: AccountUiState,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onSave: () -> Unit,
    onDraftChange: (String) -> Unit,
    onDisplayNameDraftChange: (String) -> Unit = {},
    onSignOut: () -> Unit,
    onSignOutAll: () -> Unit,
    onDevices: () -> Unit,
    onDeleteAccount: () -> Unit,
    onAvatar: () -> Unit = {},
    onChangeAvatar: () -> Unit = {},
    onRemoveAvatar: () -> Unit = {},
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
            trailingIcon = if (ui.editing) Icons.Default.Check else Icons.Outlined.Edit,
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
            // iOS `avatarHeader`: the avatar, "[change photo]" under it; both out of reach while
            // the alias is being edited.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 28.dp)
                    .alpha(if (ui.editing) 0.4f else 1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CTAvatar(
                    userId = account?.userId.orEmpty(),
                    displayName = account?.displayName.orEmpty(),
                    image = rememberAvatar(account?.avatar),
                    // iOS `MainAvatarView` at `accountSize` draws its disc about 72pt across.
                    size = 72.dp,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(enabled = !ui.editing, onClick = onAvatar),
                )
                // iOS: with a photo set, a menu — choose another, or remove it; without one, the picker.
                var photoMenu by remember { mutableStateOf(false) }
                Box {
                    Text(
                        text = "[${stringResource(R.string.change_photo)}]",
                        style = CTFont.ui(14),
                        color = if (ui.editing) CTColor.textDim else CTColor.accent,
                        modifier = Modifier.clickable(enabled = !ui.editing) {
                            if (account?.avatar != null) photoMenu = true else onChangeAvatar()
                        },
                    )
                    DropdownMenu(
                        expanded = photoMenu,
                        onDismissRequest = { photoMenu = false },
                        modifier = Modifier.background(CTColor.outMsgBg),
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.choose_photo), style = CTFont.ui(14), color = CTColor.text) },
                            leadingIcon = { Icon(Icons.Outlined.Image, contentDescription = null, tint = CTColor.text) },
                            onClick = { photoMenu = false; onChangeAvatar() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.remove_photo), style = CTFont.ui(14), color = CTColor.danger) },
                            leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = CTColor.danger) },
                            onClick = { photoMenu = false; onRemoveAvatar() },
                        )
                    }
                }
            }
            Divider(thick = true)

            SectionHeader(stringResource(R.string.account_section_identity))
            RowDivider()
            UsernameRow(ui = ui, onDraftChange = onDraftChange, onEdit = onEdit)
            RowDivider()
            SearchableRow(searchable = account?.discoverable == true)
            RowDivider()
            DisplayNameRow(ui = ui, onDraftChange = onDisplayNameDraftChange, onEdit = onEdit)
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
                    // iOS: the id is dim; the fingerprint above is the accent one.
                    valueColor = CTColor.textDim,
                )
                RowDivider()
                InfoRow(
                    label = stringResource(R.string.linked_devices),
                    onClick = onDevices.takeUnless { ui.editing },
                ) {
                    Text("[${stringResource(R.string.account_manage)}]", style = CTFont.body, color = CTColor.accent)
                }
                RowDivider()
                InfoRow(
                    label = stringResource(R.string.account_sign_out),
                    labelColor = CTColor.text,
                    onClick = onSignOut.takeUnless { ui.editing || ui.signingOut },
                ) { Chevron() }
            }
            Divider(thick = true)

            // iOS "Danger zone": the two things that cannot be taken back.
            Column(modifier = Modifier.alpha(if (ui.editing) 0.4f else 1f)) {
                SectionHeader(stringResource(R.string.account_danger_zone), color = CTColor.danger)
                RowDivider()
                InfoRow(
                    label = stringResource(R.string.account_sign_out_all),
                    labelColor = CTColor.danger.copy(alpha = 0.85f),
                    onClick = onSignOutAll.takeUnless { ui.editing || ui.signingOut },
                ) { Chevron(CTColor.danger.copy(alpha = 0.6f)) }
                RowDivider()
                InfoRow(
                    label = stringResource(R.string.account_delete),
                    labelColor = CTColor.danger,
                    onClick = onDeleteAccount.takeUnless { ui.editing },
                ) {
                    Text(
                        "[${stringResource(R.string.account_delete_action)}]",
                        style = CTFont.body,
                        color = CTColor.danger.copy(alpha = 0.6f),
                    )
                }
            }
            Divider(thick = true)

            Text(
                text = stringResource(R.string.changes_encrypted_footer),
                style = CTFont.caption,
                color = CTColor.accent.copy(alpha = 0.6f),
                modifier = Modifier.padding(horizontal = ROW_H_PAD, vertical = 16.dp),
            )
        }
    }
}

/** iOS `logout_no_backup` alert: set the phrase up first, sign out anyway, or cancel. */
@Composable
private fun NoBackupDialog(onSetUp: () -> Unit, onProceed: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CTColor.outMsgBg,
        title = { Text(stringResource(R.string.logout_no_backup_title), style = CTFont.ui(15, FontWeight.Bold), color = CTColor.text) },
        text = { Text(stringResource(R.string.logout_no_backup_message), style = CTFont.body, color = CTColor.textDim) },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = onSetUp) {
                    Text(stringResource(R.string.logout_no_backup_setup), style = CTFont.bodyEmphasis, color = CTColor.accent)
                }
                TextButton(onClick = onProceed) {
                    Text(stringResource(R.string.logout_no_backup_proceed), style = CTFont.body, color = CTColor.danger)
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel), style = CTFont.body, color = CTColor.textDim)
                }
            }
        },
    )
}

@Composable
private fun UsernameRow(ui: AccountUiState, onDraftChange: (String) -> Unit, onEdit: () -> Unit) {
    val username = ui.account?.username.orEmpty()
    // iOS: "tap to change" — the row itself opens the edit, as the nav bar's pencil does.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (ui.editing) Modifier else Modifier.clickable(onClick = onEdit))
            .padding(horizontal = ROW_H_PAD, vertical = ROW_V_PAD),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.account_username),
                style = CTFont.ui(14),
                color = CTColor.textDim,
                modifier = Modifier.weight(1f),
            )
            if (!ui.editing) {
                Text(
                    text = if (username.isEmpty()) stringResource(R.string.username_not_set) else "@$username",
                    style = CTFont.ui(14),
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
                style = CTFont.caption,
                color = if (error != null) CTColor.danger else CTColor.textDim,
            )
        }
    }
}

/** iOS `profileEditableRow(display_name)`: the name contacts see, edited with the alias. */
@Composable
private fun DisplayNameRow(ui: AccountUiState, onDraftChange: (String) -> Unit, onEdit: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (ui.editing) Modifier else Modifier.clickable(onClick = onEdit))
            .padding(horizontal = ROW_H_PAD, vertical = ROW_V_PAD),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.account_display_name),
                style = CTFont.ui(14),
                color = CTColor.textDim,
                modifier = Modifier.weight(1f),
            )
            if (!ui.editing) {
                Text(ui.account?.displayName.orEmpty(), style = CTFont.ui(14), color = CTColor.text)
            }
        }
        if (ui.editing) {
            Spacer(Modifier.height(8.dp))
            CTTextField(
                placeholder = stringResource(R.string.account_display_name),
                value = ui.draftDisplayName,
                onValueChange = onDraftChange,
            )
        } else {
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.account_display_name_hint),
                style = CTFont.caption,
                color = CTColor.textDim,
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
            style = CTFont.secondary,
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
    valueColor: Color = CTColor.accent,
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
            style = CTFont.body,
            color = if (shown == null) CTColor.textDim else valueColor,
            maxLines = 1,
        )
    }
}

@Composable
private fun InfoRow(
    label: String,
    labelColor: Color = CTColor.textDim,
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
            style = CTFont.ui(14),
            color = labelColor,
            modifier = Modifier.weight(1f),
        )
        value()
    }
}

@Composable
private fun Chevron(tint: Color = CTColor.accent) {
    Icon(
        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(CTIcon.nav),
    )
}

@Composable
private fun SectionHeader(title: String, color: Color = CTColor.accent) {
    Row(modifier = Modifier.padding(horizontal = ROW_H_PAD, vertical = 10.dp)) {
        Text(">", style = CTFont.ui(12, FontWeight.Bold), color = color)
        Spacer(Modifier.width(6.dp))
        Text(title.uppercase(), style = CTFont.ui(12, FontWeight.Bold), color = color, letterSpacing = 2.sp)
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

/** iOS `AccountSettingsLayout.rowHorizontalPadding` / `rowVerticalPadding`. */
private val ROW_H_PAD = 20.dp
private val ROW_V_PAD = 14.dp
private const val COPIED_FLASH_MS = 1_500L
