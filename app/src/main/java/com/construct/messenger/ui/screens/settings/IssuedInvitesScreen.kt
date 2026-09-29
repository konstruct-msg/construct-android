package com.construct.messenger.ui.screens.settings

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.construct.messenger.R
import com.construct.messenger.data.repository.InviteRevocation
import com.construct.messenger.data.repository.IssuedInvite
import com.construct.messenger.ui.components.CTConfirmDialog
import com.construct.messenger.ui.components.CTNavBar
import com.construct.messenger.ui.components.CTSectionGroup
import com.construct.messenger.ui.components.CTSep
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTLayout
import com.construct.messenger.ui.theme.CornerRadius
import com.construct.messenger.ui.theme.ctRegular
import com.construct.messenger.viewmodel.IssuedInvitesUiState
import com.construct.messenger.viewmodel.IssuedInvitesViewModel
import java.util.Date

/** The invite TTL in hours, for the copy: 43 200 s — "12 h". */
private const val TTL_HOURS = 12

@Composable
fun IssuedInvitesRoute(
    onNavigateBack: () -> Unit,
    viewModel: IssuedInvitesViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    var pending by remember { mutableStateOf<IssuedInvite?>(null) }
    pending?.let { invite ->
        CTConfirmDialog(
            title = stringResource(R.string.invite_revoke_confirm_title),
            message = stringResource(R.string.invite_revoke_confirm_message),
            confirmLabel = stringResource(R.string.invite_revoke),
            dismissLabel = stringResource(R.string.action_cancel),
            isDestructive = true,
            onConfirm = {
                pending = null
                viewModel.revoke(invite)
            },
            onDismiss = { pending = null },
        )
    }
    IssuedInvitesScreen(ui = ui, onNavigateBack = onNavigateBack, onRevoke = { pending = it })
}

/**
 * What this device handed out and has not yet expired.
 *
 * **Canon:** iOS `IssuedInvitesView` — two `>` lines saying the scope and what can be revoked,
 * the last outcome, then the list. One deliberate difference: Android mints each QR as its own
 * five-minute invite and journals it, so a QR row can be revoked like a link; iOS lists a QR
 * sitting as one row it cannot revoke.
 */
@Composable
private fun IssuedInvitesScreen(
    ui: IssuedInvitesUiState,
    onNavigateBack: () -> Unit,
    onRevoke: (IssuedInvite) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CTColor.bg)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        CTNavBar(
            title = stringResource(R.string.issued_invites_title),
            showBack = true,
            onBack = onNavigateBack,
        )
        Box(Modifier.fillMaxWidth().height(1.dp).background(CTColor.noise))
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(vertical = CTLayout.edgePad),
            verticalArrangement = Arrangement.spacedBy(CTLayout.edgePad),
        ) {
            Line("> " + stringResource(R.string.issued_invites_hint, TTL_HOURS))
            Line("> " + stringResource(R.string.issued_invites_revoke_scope))
            ui.lastOutcome?.let { outcome ->
                Line(
                    text = "> " + stringResource(outcome.message),
                    color = if (outcome == InviteRevocation.UNCONFIRMED) CTColor.danger else CTColor.accent,
                )
            }
            if (ui.invites.isEmpty()) {
                Line(
                    text = stringResource(R.string.issued_invites_empty, TTL_HOURS),
                    modifier = Modifier.padding(vertical = CTLayout.sectionGap),
                )
            } else {
                CTSectionGroup {
                    ui.invites.forEachIndexed { index, invite ->
                        if (index > 0) CTSep()
                        InviteRow(
                            invite = invite,
                            nowEpochSec = ui.nowEpochSec,
                            revoking = ui.revokingJti == invite.jti,
                            anyRevoking = ui.revokingJti != null,
                            onRevoke = { onRevoke(invite) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InviteRow(
    invite: IssuedInvite,
    nowEpochSec: Long,
    revoking: Boolean,
    anyRevoking: Boolean,
    onRevoke: () -> Unit,
) {
    val context = LocalContext.current
    val isQr = invite.kind == "qr"
    val time = DateFormat.getTimeFormat(context).format(Date(invite.issuedAtEpochSec * 1000))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = CTLayout.edgePad, vertical = CTLayout.chromeGap),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CTLayout.chromeGap),
    ) {
        Icon(
            imageVector = if (isQr) Icons.Default.QrCode else Icons.Default.Link,
            contentDescription = null,
            tint = CTColor.text,
            modifier = Modifier.width(20.dp).size(18.dp),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val kind = stringResource(if (isQr) R.string.issued_invite_qr else R.string.issued_invite_link)
            Text(text = "${kind.uppercase()} · $time", style = ctRegular(13), color = CTColor.text)
            Text(
                text = stringResource(R.string.issued_invite_expires, remaining(invite, nowEpochSec)),
                style = ctRegular(11),
                color = CTColor.textDim,
            )
        }
        if (revoking) {
            CircularProgressIndicator(color = CTColor.textDim, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
        } else {
            val shape = RoundedCornerShape(CornerRadius.small)
            Text(
                text = stringResource(R.string.invite_revoke).lowercase(),
                style = ctRegular(11),
                color = CTColor.danger,
                modifier = Modifier
                    .background(CTColor.bgMsg, shape)
                    .border(1.dp, CTColor.danger.copy(alpha = 0.5f), shape)
                    .clickable(enabled = !anyRevoking, onClick = onRevoke)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
    }
}

@Composable
private fun remaining(invite: IssuedInvite, nowEpochSec: Long): String {
    val seconds = (invite.issuedAtEpochSec + invite.ttlSeconds - nowEpochSec).coerceAtLeast(0)
    val hours = (seconds / 3600).toInt()
    val minutes = ((seconds % 3600) / 60).toInt()
    return if (hours > 0) {
        stringResource(R.string.duration_hours_minutes, hours, minutes)
    } else {
        stringResource(R.string.duration_minutes, minutes.coerceAtLeast(1))
    }
}

private val InviteRevocation.message: Int
    get() = when (this) {
        InviteRevocation.REVOKED -> R.string.invite_revoked
        InviteRevocation.ALREADY_USED -> R.string.invite_already_used
        InviteRevocation.UNCONFIRMED -> R.string.invite_revoke_unconfirmed
    }

@Composable
private fun Line(text: String, modifier: Modifier = Modifier, color: androidx.compose.ui.graphics.Color = CTColor.textDim) {
    Text(
        text = text,
        style = ctRegular(12),
        color = color,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = CTLayout.edgePad),
    )
}

@Preview(backgroundColor = 0xFF090909, showBackground = true, heightDp = 600, widthDp = 360)
@Composable
private fun IssuedInvitesScreenPreview() {
    val now = 1_759_100_000L
    IssuedInvitesScreen(
        ui = IssuedInvitesUiState(
            invites = listOf(
                IssuedInvite("a", "link", now - 600, 43_200),
                IssuedInvite("b", "qr", now - 60, 300),
            ),
            nowEpochSec = now,
            lastOutcome = InviteRevocation.REVOKED,
        ),
        onNavigateBack = {},
        onRevoke = {},
    )
}
