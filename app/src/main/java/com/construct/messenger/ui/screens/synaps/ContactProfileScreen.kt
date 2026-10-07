package com.construct.messenger.ui.screens.synaps

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.construct.messenger.BuildConfig
import com.construct.messenger.R
import com.construct.messenger.data.model.ContactTrustAlert
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
import com.construct.messenger.ui.theme.CornerRadius
import com.construct.messenger.viewmodel.ContactProfileUiState
import com.construct.messenger.viewmodel.ContactProfileViewModel
import com.construct.messenger.viewmodel.ShareOutcome

private enum class Confirm { BLOCK, UNBLOCK, REPORT, DELETE }

/** iOS `UserProfileView` row insets: 20 from the edges, 14 above and below. */
private val ROW_H = 20.dp
private val ROW_V = 14.dp

/**
 * A contact's card. **Canon:** iOS `UserProfileView` — the avatar, then flat sections under
 * `> TITLE` headers: identity, actions, security, the danger zone, and the closing line.
 *
 * Not here yet: calls — the voice call row is iOS's own disabled "soon" row — and the avatar in
 * a shared profile, which needs media. "Remove contact" stays on every entry point:
 * removing is local, and Android has no Synaps prune to leave it to.
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
    var editingLocalName by remember { mutableStateOf(false) }

    LaunchedEffect(ui.removed) {
        if (ui.removed) onNavigateBack()
    }
    // Back from the safety numbers or the chat, the session may be a different one.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshSession() }

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

    if (editingLocalName) {
        LocalNameDialog(
            current = ui.localName.orEmpty(),
            onSave = { editingLocalName = false; viewModel.setLocalName(it) },
            onClear = { editingLocalName = false; viewModel.setLocalName(null) },
            onDismiss = { editingLocalName = false },
        )
    }

    ui.shareOutcome?.let { outcome ->
        AlertDialog(
            onDismissRequest = viewModel::shareOutcomeShown,
            containerColor = CTColor.outMsgBg,
            text = {
                Text(
                    stringResource(
                        when (outcome) {
                            ShareOutcome.SHARED -> R.string.profile_shared_successfully
                            ShareOutcome.FAILED -> R.string.failed_to_share_profile
                            ShareOutcome.STOPPED -> R.string.profile_sharing_stopped
                        },
                    ),
                    style = CTFont.body,
                    color = CTColor.textDim,
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::shareOutcomeShown) {
                    Text(stringResource(R.string.close), style = CTFont.bodyEmphasis, color = CTColor.accent)
                }
            },
        )
    }

    ui.reportAccepted?.let { accepted ->
        AlertDialog(
            onDismissRequest = viewModel::reportShown,
            containerColor = CTColor.outMsgBg,
            text = {
                Text(
                    stringResource(if (accepted) R.string.contact_report_sent else R.string.contact_report_failed),
                    style = CTFont.body,
                    color = CTColor.textDim,
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::reportShown) {
                    Text(stringResource(R.string.close), style = CTFont.bodyEmphasis, color = CTColor.accent)
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
        CTNavBar(title = stringResource(R.string.profile_title), showBack = true, onBack = onNavigateBack)
        FlatDivider(thick = true)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp),
        ) {
            AvatarHeader(ui)
            FlatDivider(thick = true)
            IdentitySection(ui, onEditLocalName = { editingLocalName = true })
            FlatDivider(thick = true)
            ActionsSection(
                ui = ui,
                showOpenChat = !viewModel.fromChat,
                onOpenChat = { onOpenChat(viewModel.userId) },
                onToggleSharing = viewModel::toggleSharing,
            )
            FlatDivider(thick = true)
            SecuritySection(
                ui = ui,
                onSafetyNumbers = { onOpenSafetyNumbers(viewModel.userId) },
                onAcknowledge = viewModel::acknowledgeSecurityNotice,
            )
            FlatDivider(thick = true)
            SectionHeader(stringResource(R.string.contact_danger_zone), color = CTColor.danger)
            RowDivider()
            ActionRow(
                label = stringResource(if (ui.isBlocked) R.string.profile_unblock_user else R.string.profile_block_user),
                color = if (ui.isBlocked) CTColor.text else CTColor.danger,
                enabled = !ui.busy,
            ) { confirm = if (ui.isBlocked) Confirm.UNBLOCK else Confirm.BLOCK }
            RowDivider()
            ActionRow(label = stringResource(R.string.contact_report), color = CTColor.danger, enabled = !ui.busy) {
                confirm = Confirm.REPORT
            }
            RowDivider()
            ActionRow(label = stringResource(R.string.contact_delete), color = CTColor.danger, enabled = !ui.busy) {
                confirm = Confirm.DELETE
            }
            FlatDivider(thick = true)
            Text(
                text = "> ${stringResource(R.string.profile_e2e)}",
                style = CTFont.caption,
                color = CTColor.accent.copy(alpha = 0.5f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ROW_H, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun AvatarHeader(ui: ContactProfileUiState) {
    val image: ImageBitmap? = rememberAvatar(ui.avatar)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        CTAvatar(userId = ui.userId, displayName = ui.name, image = image, size = 96.dp)
        if (ui.isBlocked) {
            Row(
                modifier = Modifier
                    .background(CTColor.danger.copy(alpha = 0.14f), RoundedCornerShape(CornerRadius.small))
                    .padding(horizontal = 9.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(Icons.Default.Block, contentDescription = null, tint = CTColor.danger, modifier = Modifier.size(CTIcon.caption))
                Text(stringResource(R.string.profile_blocked_badge), style = CTFont.ui(11, FontWeight.SemiBold), color = CTColor.danger)
            }
        }
    }
}

@Composable
private fun IdentitySection(ui: ContactProfileUiState, onEditLocalName: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    SectionHeader(stringResource(R.string.profile_identity))
    RowDivider()
    ProfileRow(stringResource(R.string.profile_username)) {
        Text("<@${ui.username.ifBlank { "—" }}>", style = CTFont.ui(14), color = CTColor.textDim)
    }
    RowDivider()
    ProfileRow(stringResource(R.string.profile_display_name)) {
        Text(ui.displayName, style = CTFont.ui(14), color = CTColor.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    RowDivider()
    // iOS: a name only this device shows, overriding every other one wherever they are named.
    ProfileRow(stringResource(R.string.local_name), modifier = Modifier.clickable(onClick = onEditLocalName)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val local = ui.localName
            Text(
                text = local ?: stringResource(R.string.local_name_unset),
                style = CTFont.ui(14),
                color = if (local != null) CTColor.text else CTColor.textDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(Icons.Default.Edit, contentDescription = null, tint = CTColor.accent.copy(alpha = 0.7f), modifier = Modifier.size(CTIcon.caption))
        }
    }
    RowDivider()
    val fingerprint = ui.fingerprint
    if (fingerprint != null) {
        val copyHint = stringResource(R.string.profile_fingerprint_copy)
        ProfileRow(
            label = stringResource(R.string.profile_fingerprint),
            modifier = Modifier
                .clickable { clipboard.setText(AnnotatedString(fingerprint)) }
                .semantics { onClick(label = copyHint) { clipboard.setText(AnnotatedString(fingerprint)); true } },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(fingerprint, style = CTFont.secondary, color = CTColor.accent, maxLines = 1)
                Icon(Icons.Outlined.ContentCopy, contentDescription = null, tint = CTColor.textDim, modifier = Modifier.size(CTIcon.caption))
            }
        }
    } else {
        ProfileRow(stringResource(R.string.profile_fingerprint)) {
            Text(stringResource(R.string.profile_fingerprint_unknown), style = CTFont.body, color = CTColor.textDim)
        }
    }
    // The server's user id is addressing, not identity: debug builds only, as on iOS.
    if (BuildConfig.DEBUG) {
        RowDivider()
        ProfileRow(stringResource(R.string.profile_user_id)) {
            val id = ui.userId
            Text(
                text = if (id.length > 12) "${id.take(8)}...${id.takeLast(2)}" else id,
                style = CTFont.body,
                color = CTColor.textDim.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun ActionsSection(
    ui: ContactProfileUiState,
    showOpenChat: Boolean,
    onOpenChat: () -> Unit,
    onToggleSharing: () -> Unit,
) {
    SectionHeader(stringResource(R.string.profile_actions))
    RowDivider()
    if (showOpenChat) {
        ActionRow(label = stringResource(R.string.contact_open_chat), color = CTColor.accent, onClick = onOpenChat)
        RowDivider()
    }
    // iOS: the voice call row, accent, only while no call is on.
    com.construct.messenger.ui.screens.calls.rememberCallAction(ui.userId)?.let { call ->
        ActionRow(label = stringResource(R.string.profile_call_voice), color = CTColor.accent, onClick = call)
        RowDivider()
    }
    // iOS: accent to share, plain to stop; the row waits while the share is in flight.
    ActionRow(
        label = stringResource(if (ui.amSharing) R.string.stop_sharing_profile else R.string.share_my_profile),
        color = if (ui.amSharing) CTColor.text else CTColor.accent,
        enabled = !ui.sharing,
        loading = ui.sharing,
        onClick = onToggleSharing,
    )
    if (ui.sharingWithMe) {
        RowDivider()
        Text(
            text = stringResource(R.string.sharing_with_you),
            style = CTFont.caption,
            color = CTColor.textDim,
            modifier = Modifier.padding(horizontal = ROW_H, vertical = 10.dp),
        )
    }
}

@Composable
private fun SecuritySection(ui: ContactProfileUiState, onSafetyNumbers: () -> Unit, onAcknowledge: () -> Unit) {
    SectionHeader(stringResource(R.string.profile_security))
    RowDivider()
    ui.trustAlert?.let { alert ->
        SecurityNoticeBlock(alert, ui.name, onVerify = onSafetyNumbers, onAcknowledge = onAcknowledge)
        RowDivider()
    }
    val session = ui.session
    val hasSession = session?.hasSession == true
    ProfileRow(stringResource(R.string.profile_encryption)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CTStatusBadge(status = if (hasSession) CTStatus.OK else CTStatus.OFF, size = 13.dp)
            if (hasSession) Text(stringResource(R.string.profile_encrypted), style = CTFont.body, color = CTColor.text)
        }
    }
    RowDivider()
    ProfileRow(label = "") {
        Text(
            text = if (session != null && hasSession && session.suiteId > 0) {
                suiteName(session.suiteId) + if (session.pqDegraded) " · PQXDH degraded" else ""
            } else {
                stringResource(R.string.profile_no_session)
            },
            style = CTFont.body,
            color = if (hasSession) CTColor.text else CTColor.textDim,
        )
    }
    RowDivider()
    ProfileRow(stringResource(R.string.safety_numbers), modifier = Modifier.clickable(onClick = onSafetyNumbers)) {
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = CTColor.textDim, modifier = Modifier.size(CTIcon.row))
    }
}

/** iOS `keyChangeWarningBlock`: stays until they verify or acknowledge it. */
@Composable
private fun SecurityNoticeBlock(alert: ContactTrustAlert, name: String, onVerify: () -> Unit, onAcknowledge: () -> Unit) {
    val (title, body) = com.construct.messenger.ui.screens.chat.trustAlertText(alert, name)
    val control = RoundedCornerShape(CornerRadius.control)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CTColor.danger.copy(alpha = 0.08f))
            .padding(horizontal = ROW_H, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.GppMaybe, contentDescription = null, tint = CTColor.danger, modifier = Modifier.size(CTIcon.row))
            Text(title, style = CTFont.ui(12, FontWeight.Bold), color = CTColor.danger)
        }
        Text(body, style = CTFont.caption, color = CTColor.textDim)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = stringResource(R.string.key_change_verify),
                style = CTFont.ui(12, FontWeight.Bold),
                color = CTColor.bg,
                modifier = Modifier
                    .background(CTColor.danger, control)
                    .clickable(onClick = onVerify)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
            Text(
                text = stringResource(R.string.security_notice_acknowledge),
                style = CTFont.secondary,
                color = CTColor.accent,
                modifier = Modifier
                    .background(CTColor.bgMsg, control)
                    .border(1.dp, CTColor.accent.copy(alpha = 0.5f), control)
                    .clickable(onClick = onAcknowledge)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

/** iOS `.alert("local_name")`: a field, save, clear when there is one, cancel; the footer says who sees it. */
@Composable
private fun LocalNameDialog(current: String, onSave: (String) -> Unit, onClear: () -> Unit, onDismiss: () -> Unit) {
    var draft by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CTColor.outMsgBg,
        title = { Text(stringResource(R.string.local_name), style = CTFont.ui(15, FontWeight.Bold), color = CTColor.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.local_name_footer), style = CTFont.secondary, color = CTColor.textDim)
                CTTextField(
                    placeholder = stringResource(R.string.local_name_placeholder),
                    value = draft,
                    onValueChange = { draft = it },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(draft) }) {
                Text(stringResource(R.string.action_save).replaceFirstChar { it.titlecase() }, style = CTFont.bodyEmphasis, color = CTColor.accent)
            }
        },
        dismissButton = {
            Row {
                if (current.isNotEmpty()) {
                    TextButton(onClick = onClear) {
                        Text(stringResource(R.string.local_name_clear), style = CTFont.body, color = CTColor.danger)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel), style = CTFont.body, color = CTColor.textDim)
                }
            }
        },
    )
}

/** Must follow `SuiteID` in construct-core `crypto/suite_id.rs`, as iOS `cryptoSuiteName` does. */
private fun suiteName(suiteId: Int): String = when (suiteId) {
    1 -> "X25519 · ChaCha20-Poly1305"
    2 -> "PQ Hybrid · X25519+ML-KEM-768 · ML-DSA-65"
    3 -> "X25519 · ChaCha20-Poly1305 · PQ Ratchet v1 (retired)"
    // PQ_RATCHET since core 0.24.0 (PQR-2); iOS still names only 1–3 (construct-docs TODO).
    4 -> "X25519 · ChaCha20-Poly1305 · PQ Ratchet (ML-KEM-768)"
    else -> "Suite $suiteId"
}

@Composable
private fun SectionHeader(title: String, color: Color = CTColor.accent) {
    Text(
        text = "> ${title.uppercase()}",
        style = CTFont.ui(12, FontWeight.Bold),
        color = color,
        letterSpacing = 2.sp,
        modifier = Modifier.padding(horizontal = ROW_H, vertical = 10.dp),
    )
}

@Composable
private fun ProfileRow(label: String, modifier: Modifier = Modifier, value: @Composable () -> Unit) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = ROW_H, vertical = ROW_V),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (label.isNotEmpty()) {
            Text(label.lowercase(), style = CTFont.ui(14), color = CTColor.textDim)
            Spacer(Modifier.size(12.dp))
        }
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { value() }
    }
}

@Composable
private fun ActionRow(
    label: String,
    color: Color,
    enabled: Boolean = true,
    loading: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = ROW_H, vertical = ROW_V),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label.lowercase(), style = CTFont.ui(14), color = color, modifier = Modifier.weight(1f))
        // iOS `actionRow(isLoading:)`: a share can take as long as opening a session does.
        if (loading) {
            CircularProgressIndicator(color = CTColor.textDim, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
        } else {
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = color.copy(alpha = 0.6f), modifier = Modifier.size(CTIcon.row))
        }
    }
}

/** Between sections: `noise`, 1 dp, edge to edge. */
@Composable
private fun FlatDivider(thick: Boolean = false) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(if (thick) CTColor.noise else CTColor.noise.copy(alpha = 0.5f)),
    )
}

/** Between rows: `noise` at 35 %, inset 20 dp. */
@Composable
private fun RowDivider() {
    Box(
        Modifier
            .padding(horizontal = ROW_H)
            .fillMaxWidth()
            .height(1.dp)
            .background(CTColor.noise.copy(alpha = 0.35f)),
    )
}
