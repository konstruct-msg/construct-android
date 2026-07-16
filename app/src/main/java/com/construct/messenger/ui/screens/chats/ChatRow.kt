package com.construct.messenger.ui.screens.chats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.construct.messenger.data.model.ChatSummary
import com.construct.messenger.ui.components.CTAvatar
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.ctBold
import com.construct.messenger.ui.theme.ctRegular
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One row in the chats list.
 *
 * **Canon:** iOS `ConstructTheme.swift` → `struct ChatRowView`.
 * - 44dp avatar on the left.
 * - Title: `@username` when available, otherwise `DISPLAY_NAME` uppercased.
 * - Preview line in `ctRegular(12)` + `textDim`.
 * - Timestamp and unread badge on the trailing edge.
 */
@Composable
fun ChatRow(
    chat: ChatSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CTAvatar(
            userId = chat.contactId,
            displayName = chat.displayName,
            size = 44.dp,
        )
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = if (chat.username.isNotEmpty()) "@${chat.username}" else chat.displayName.uppercase(),
                style = ctBold(13),
                color = CTColor.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(2.dp))
            Text(
                text = chat.lastMessagePreview ?: "—",
                style = ctRegular(12),
                color = CTColor.textDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(
            horizontalAlignment = Alignment.End
        ) {
            chat.lastMessageTime?.let { time ->
                Text(
                    text = formatChatTimestamp(time),
                    style = ctRegular(11),
                    color = CTColor.textDim,
                )
            }
            if (chat.unreadCount > 0) {
                Spacer(Modifier.width(4.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(CTColor.accent)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = chat.unreadCount.coerceAtMost(99).toString(),
                        style = ctBold(10),
                        color = CTColor.bg,
                    )
                }
            }
        }
    }
}

private fun formatChatTimestamp(timeMillis: Long): String {
    val now = System.currentTimeMillis()
    val date = Date(timeMillis)
    return if (isSameDay(now, timeMillis)) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)
    } else {
        SimpleDateFormat("dd.MM", Locale.getDefault()).format(date)
    }
}

private fun isSameDay(a: Long, b: Long): Boolean {
    val formatter = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
    return formatter.format(Date(a)) == formatter.format(Date(b))
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
