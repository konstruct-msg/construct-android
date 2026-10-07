package com.construct.messenger.ui.screens.chats

import android.content.Context
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.construct.messenger.R
import com.construct.messenger.data.model.ChatSummary
import com.construct.messenger.ui.components.CTAvatar
import com.construct.messenger.ui.components.CTRowDivider
import com.construct.messenger.ui.components.rememberAvatar
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTFont
import com.construct.messenger.ui.theme.CTIcon
import com.construct.messenger.ui.theme.CTLayout
import com.construct.messenger.ui.theme.CornerRadius
import java.util.Date

/**
 * One row in the chats list.
 *
 * **Canon:** iOS `ConstructTheme.swift` → `struct ChatRowView`.
 * - 44dp avatar on the left.
 * - Title: `@username` when available, otherwise `DISPLAY_NAME` uppercased.
 * - Preview line in `CTFont.secondary` + `textDim`.
 * - Timestamp and unread badge on the trailing edge.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatRow(
    chat: ChatSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    Column(modifier = modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        // Canon: iOS `ChatRowLayout` — avatar (40), 10 gap; name with the time on its line,
        // the preview with the unread badge beneath; List row insets around it.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CTAvatar(
                userId = chat.contactId,
                displayName = chat.displayName,
                image = rememberAvatar(chat.avatar),
                size = 40.dp,
            )
            Spacer(Modifier.width(CTLayout.chromeGap))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // The name takes all the room the time leaves, so a long name runs to the
                    // time and the time sits on the trailing edge whatever the name's length.
                    // (A weighted spacer beside a weighted name split the room in half: long
                    // names were cut at the middle, short ones left the time short of the edge.)
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        // iOS `resolvedDisplayName`: the name as given, never upper-cased; the
                        // username only when there is no name.
                        Text(
                            text = chat.displayName.ifBlank { chat.username },
                            style = CTFont.bodyEmphasis,
                            color = CTColor.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (chat.alerted) {
                            Icon(
                                androidx.compose.material.icons.Icons.Filled.GppMaybe,
                                contentDescription = stringResource(R.string.kt_warning),
                                tint = CTColor.danger,
                                modifier = Modifier.padding(start = 4.dp).size(CTIcon.caption),
                            )
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                    chat.lastMessageTime?.let { time ->
                        Text(
                            text = rowTimestamp(context, time),
                            style = CTFont.caption,
                            color = CTColor.textDim,
                            maxLines = 1,
                        )
                    }
                    // iOS: a pinned chat with nothing unread shows the pin after the time.
                    if (chat.isPinned && chat.unreadCount == 0) {
                        Spacer(Modifier.width(CTLayout.inlinePad))
                        Icon(
                            imageVector = Icons.Filled.PushPin,
                            contentDescription = stringResource(R.string.chat_pinned),
                            tint = CTColor.textDim,
                            modifier = Modifier.size(CTIcon.caption),
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = chat.lastMessagePreview.orEmpty(),
                        style = CTFont.secondary,
                        color = CTColor.textDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (chat.unreadCount > 0) {
                        Spacer(Modifier.width(4.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(CornerRadius.badge))
                                .background(CTColor.accent)
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = if (chat.unreadCount < 10_000) chat.unreadCount.toString() else "9999+",
                                style = CTFont.badge,
                                color = CTColor.bg,
                            )
                        }
                    }
                }
            }
        }
        // The List separator, from the text column to the trailing inset.
        CTRowDivider(indent = 16.dp + 40.dp + CTLayout.chromeGap, modifier = Modifier.padding(end = 16.dp))
    }
}

/** iOS `rowTimestampText`: today's time; otherwise "Yesterday" where it applies, else a short date. */
private fun rowTimestamp(context: Context, timeMillis: Long): String {
    val date = Date(timeMillis)
    if (DateUtils.isToday(timeMillis)) return DateFormat.getTimeFormat(context).format(date)
    if (DateUtils.isToday(timeMillis + DateUtils.DAY_IN_MILLIS)) {
        return DateUtils.getRelativeTimeSpanString(
            timeMillis, System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS,
        ).toString()
    }
    return DateFormat.getDateFormat(context).format(date)
}

@Preview(backgroundColor = 0xFF090909, showBackground = true)
@Composable
private fun ChatRowPreview() {
    Column {
        ChatRow(
            chat = ChatSummary(
                contactId = "14f28d31-aaaa-bbbb-cccc-000000000001",
                displayName = "Silent Fox",
                username = "silent_fox",
                lastMessagePreview = "The keys are rotated.",
                lastMessageTime = System.currentTimeMillis() - 5 * 60 * 1000,
                unreadCount = 2,
            ),
            onClick = {},
        )
        ChatRow(
            chat = ChatSummary(
                contactId = "14f28d31-aaaa-bbbb-cccc-000000000002",
                displayName = "Swift Wolf",
                lastMessagePreview = "See you in the mesh.",
                lastMessageTime = System.currentTimeMillis() - 47 * 60 * 1000,
                unreadCount = 0,
            ),
            onClick = {},
        )
        ChatRow(
            chat = ChatSummary(
                contactId = "14f28d31-aaaa-bbbb-cccc-000000000003",
                displayName = "Deprecated Printer",
                username = "deprecated_printer",
                lastMessagePreview = "Out of toner, send help.",
                lastMessageTime = System.currentTimeMillis() - 3 * 60 * 60 * 1000,
                unreadCount = 12,
                isPinned = true,
            ),
            onClick = {},
        )
    }
}
