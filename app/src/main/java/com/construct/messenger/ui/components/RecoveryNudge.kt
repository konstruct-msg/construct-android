package com.construct.messenger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.construct.messenger.R
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTFont
import com.construct.messenger.ui.theme.CTIcon
import com.construct.messenger.ui.theme.CTSpace
import com.construct.messenger.ui.theme.CornerRadius

/**
 * The quiet reminder that the recovery key is not copied yet, above the chat list
 * (`ACCOUNT_RECOVERY_CLIENT_SPEC` — "sticky banner, not blocking … Settings and/or Chats list";
 * `decisions/recovery-key-backup-is-deferred-not-skipped.md`). The key was made at registration
 * without asking; this only says it is waiting. In the accent, not in red — Settings keeps the
 * warning — and put away with its own cross. Tapping it shows the twelve words.
 */
@Composable
fun RecoveryNudge(onOpen: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(CornerRadius.small)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(CTColor.bgMsg, shape)
            .border(0.5.dp, CTColor.noise, shape)
            .clickable(onClick = onOpen)
            .padding(start = CTSpace.m, top = CTSpace.s, bottom = CTSpace.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CTSpace.m),
    ) {
        Icon(Icons.Filled.Key, contentDescription = null, tint = CTColor.accent, modifier = Modifier.size(CTIcon.nav))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CTSpace.xs)) {
            Text(stringResource(R.string.recovery_nudge_title), style = CTFont.body, color = CTColor.text)
            Text(stringResource(R.string.recovery_nudge_body), style = CTFont.caption, color = CTColor.textDim)
        }
        IconButton(onClick = onDismiss) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close), tint = CTColor.textDim, modifier = Modifier.size(CTIcon.row))
        }
    }
}

@Preview(widthDp = 360)
@Composable
private fun RecoveryNudgePreview() {
    RecoveryNudge(onOpen = {}, onDismiss = {}, modifier = Modifier.padding(CTSpace.s))
}
