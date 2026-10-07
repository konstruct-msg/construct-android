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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.construct.messenger.R
import com.construct.messenger.data.repository.InviteRevocation
import com.construct.messenger.ui.components.CTConfirmDialog
import com.construct.messenger.ui.components.CTNavBar
import com.construct.messenger.ui.components.CTSectionGroup
import com.construct.messenger.ui.components.CTSep
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTFont
import com.construct.messenger.ui.theme.CTIcon
import com.construct.messenger.ui.theme.CTLayout
import com.construct.messenger.ui.theme.CornerRadius
import com.construct.messenger.viewmodel.IssuedAct
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
    var pending by remember { mutableStateOf<IssuedAct?>(null) }
    pending?.let { act ->
        CTConfirmDialog(
            title = stringResource(R.string.invite_revoke_confirm_title),
            message = stringResource(R.string.invite_revoke_confirm_message),
            confirmLabel = stringResource(R.string.invite_revoke),
            dismissLabel = stringResource(R.string.action_cancel),
            isDestructive = true,
            onConfirm = {
                pending = null
                viewModel.revoke(act)
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
 * the last outcome, then the list; one QR showing is one row with its count of live codes. One
 * deliberate difference: that row can be revoked here — every live code of the showing, at most
 * ten — where iOS offers it only for links.
 */
@Composable
private fun IssuedInvitesScreen(
    ui: IssuedInvitesUiState,
    onNavigateBack: () -> Unit,
    onRevoke: (IssuedAct) -> Unit,
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
            if (ui.acts.isEmpty()) {
                Line(
                    text = stringResource(R.string.issued_invites_empty, TTL_HOURS),
                    modifier = Modifier.padding(vertical = CTLayout.sectionGap),
                )
            } else {
                CTSectionGroup {
                    ui.acts.forEachIndexed { index, act ->
                        if (index > 0) CTSep()
                        InviteRow(
                            act = act,
                            nowEpochSec = ui.nowEpochSec,
                            revoking = ui.revokingId == act.id,
                            anyRevoking = ui.revokingId != null,
                            onRevoke = { onRevoke(act) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InviteRow(
    act: IssuedAct,
    nowEpochSec: Long,
    revoking: Boolean,
    anyRevoking: Boolean,
    onRevoke: () -> Unit,
) {
    val context = LocalContext.current
    val isQr = act.isQr
    val time = DateFormat.getTimeFormat(context).format(Date(act.startedAtEpochSec * 1000))
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
            modifier = Modifier.width(20.dp).size(CTIcon.nav),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val kind = if (isQr) {
                stringResource(R.string.issued_invite_qr_fmt, act.liveJtis.size)
            } else {
                stringResource(R.string.issued_invite_link)
            }
            Text(text = "${kind.uppercase()} · $time", style = CTFont.body, color = CTColor.text)
            Text(
                text = stringResource(R.string.issued_invite_expires, remaining(act, nowEpochSec)),
                style = CTFont.caption,
                color = CTColor.textDim,
            )
        }
        if (revoking) {
            CircularProgressIndicator(color = CTColor.textDim, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
        } else {
            val shape = RoundedCornerShape(CornerRadius.small)
            Text(
                text = stringResource(R.string.invite_revoke).lowercase(),
                style = CTFont.body,
                color = CTColor.danger,
                modifier = Modifier
                    .clip(shape)
                    .background(CTColor.bgMsg)
                    .border(1.dp, CTColor.danger.copy(alpha = 0.4f), shape)
                    .clickable(enabled = !anyRevoking, onClick = onRevoke)
                    .padding(horizontal = 20.dp, vertical = CTLayout.chromeGap),
            )
        }
    }
}

@Composable
private fun remaining(act: IssuedAct, nowEpochSec: Long): String {
    val seconds = (act.expiresAtEpochSec - nowEpochSec).coerceAtLeast(0)
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
        style = CTFont.caption,
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
            acts = listOf(
                IssuedAct("a", isQr = false, listOf("a"), now - 600, now + 42_600),
                IssuedAct("qr:s", isQr = true, listOf("b", "c", "d"), now - 90, now + 270),
            ),
            nowEpochSec = now,
            lastOutcome = InviteRevocation.REVOKED,
        ),
        onNavigateBack = {},
        onRevoke = {},
    )
}
