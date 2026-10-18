package com.construct.messenger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.SmsFailed
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.construct.messenger.R
import com.construct.messenger.data.repository.MailboxGap
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTFont
import com.construct.messenger.ui.theme.CTIcon
import com.construct.messenger.ui.theme.CTSpace
import com.construct.messenger.ui.theme.CornerRadius
import java.text.DateFormat
import java.util.Date

/**
 * "Messages from … to … may not have arrived" — once, above the chat list, until closed
 * (`MailboxGapNotice`, `decisions/mailbox-gap-is-reported.md`). Built like [RecoveryNudge]: in the
 * accent, not red — nothing here can be done about it but know. **Canon:** iOS `MailboxGapRow`.
 */
@Composable
fun MailboxGapRow(interval: MailboxGap.Interval, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val format = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    val from = format.format(Date(interval.fromMs))
    val body = interval.untilMs?.let { stringResource(R.string.mailbox_gap_body, from, format.format(Date(it))) }
        ?: stringResource(R.string.mailbox_gap_body_open, from)
    val shape = RoundedCornerShape(CornerRadius.small)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(CTColor.bgMsg, shape)
            .border(0.5.dp, CTColor.noise, shape)
            .padding(start = CTSpace.m, top = CTSpace.s, bottom = CTSpace.s),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(CTSpace.m),
    ) {
        Icon(
            Icons.Outlined.SmsFailed,
            contentDescription = null,
            tint = CTColor.accent,
            modifier = Modifier.padding(top = CTSpace.xs).size(CTIcon.nav),
        )
        Column(Modifier.weight(1f).padding(top = CTSpace.xs), verticalArrangement = Arrangement.spacedBy(CTSpace.xs)) {
            Text(stringResource(R.string.mailbox_gap_title), style = CTFont.body, color = CTColor.text)
            Text(body, style = CTFont.caption, color = CTColor.textDim)
        }
        IconButton(onClick = onDismiss) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close), tint = CTColor.textDim, modifier = Modifier.size(CTIcon.row))
        }
    }
}

@Preview(widthDp = 360)
@Composable
private fun MailboxGapRowPreview() {
    // Wall-clock fields, not epoch ms: the screenshot reads the same in any time zone.
    fun at(day: Int, hour: Int) = java.util.Calendar.getInstance().apply { clear(); set(2026, java.util.Calendar.OCTOBER, day, hour, 0) }.timeInMillis
    MailboxGapRow(MailboxGap.Interval(at(3, 4), at(10, 14)), onDismiss = {}, modifier = Modifier.padding(CTSpace.s))
}
