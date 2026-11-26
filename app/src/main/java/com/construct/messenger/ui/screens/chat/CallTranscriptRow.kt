package com.construct.messenger.ui.screens.chat

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.construct.messenger.R
import com.construct.messenger.calls.CallRecordStatus
import com.construct.messenger.calls.CallTranscript
import com.construct.messenger.data.model.CallHistoryEntry
import com.construct.messenger.data.model.Message
import com.construct.messenger.ui.screens.calls.directionColor
import com.construct.messenger.ui.screens.calls.formattedDuration
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTFont
import com.construct.messenger.ui.theme.CTIcon
import com.construct.messenger.ui.theme.CTSpace
import com.construct.messenger.ui.theme.CornerRadius
import java.util.Date

/**
 * A call in the conversation ([CallTranscript]): centred, the direction's mark, what happened and
 * how long, the time; a tap calls back. What the call was is read from the log ([entry]); a call
 * the log no longer holds in its newest rows shows the words its row was written with.
 * **Canon:** iOS `CallTranscriptRowView`.
 */
@Composable
fun CallTranscriptRow(message: Message, entry: CallHistoryEntry?, onCallBack: (() -> Unit)?) {
    val context = LocalContext.current
    val incoming = entry?.incoming ?: !message.isOutgoing
    val words = entry?.let {
        val label = stringResource(CallTranscript.labelRes(it.incoming, CallRecordStatus.valueOf(it.status.name)))
        val length = formattedDuration(it.durationSeconds).takeIf { _ -> it.status == CallHistoryEntry.Status.COMPLETED }
        if (length != null) "$label · $length" else label
    } ?: message.body
    val missed = entry?.status == CallHistoryEntry.Status.MISSED
    val shape = RoundedCornerShape(CornerRadius.control)
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = CTSpace.xs), contentAlignment = Alignment.Center) {
        Row(
            modifier = Modifier
                .clip(shape)
                .background(CTColor.bgMsg)
                .border(androidx.compose.ui.unit.Dp.Hairline, CTColor.noise, shape)
                .clickable(enabled = onCallBack != null) { onCallBack?.invoke() }
                .padding(horizontal = CTSpace.m, vertical = CTSpace.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CTSpace.xs),
        ) {
            Icon(
                imageVector = if (incoming) Icons.AutoMirrored.Filled.CallReceived else Icons.AutoMirrored.Filled.CallMade,
                contentDescription = null,
                tint = entry?.let(::directionColor) ?: CTColor.textDim,
                modifier = Modifier.size(CTIcon.caption),
            )
            Text(text = words, style = CTFont.secondary, color = if (missed) CTColor.danger else CTColor.textDim)
            Text(
                text = DateFormat.getTimeFormat(context).format(Date(message.timestamp)),
                style = CTFont.caption,
                color = CTColor.textDim,
            )
        }
    }
}
