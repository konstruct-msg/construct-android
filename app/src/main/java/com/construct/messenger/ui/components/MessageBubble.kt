@file:OptIn(ExperimentalFoundationApi::class)

package com.construct.messenger.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.filled.Circle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.construct.messenger.R
import com.construct.messenger.data.model.DeliveryStatus
import com.construct.messenger.data.model.Message
import com.construct.messenger.data.model.MessageMedia
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.Spacing
import com.construct.messenger.ui.theme.ctMessage
import com.construct.messenger.ui.theme.ctRegular
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Simple E2EE message bubble.
 *
 * **Canon:** iOS `MessageBubbleRegularView` + `ANDROID_ONBOARDING.md` §5.4.
 * - Incoming: `CTColor.bgMsg` background + 0.5dp `CTColor.noise` border.
 * - Outgoing: `CTColor.accent` background.
 * - 10dp rounded corners.
 *
 * Long-press opens Reply, Edit (our own text), Copy, and Delete. Delete removes the
 * row on this phone. [replyLabel] is the quoted message's one or two lines; null
 * means this message is not a reply. The quote strip jumps to the original.
 *
 * @param message Message to display.
 * @param isLastInGroup Whether this bubble is the last one in a consecutive group.
 */
@Composable
fun MessageBubble(
    message: Message,
    modifier: Modifier = Modifier,
    isLastInGroup: Boolean = true,
    replyLabel: String? = null,
    onLongPress: () -> Unit = {},
    menuExpanded: Boolean = false,
    onDismissMenu: () -> Unit = {},
    onReply: () -> Unit = {},
    onCopy: () -> Unit = {},
    onEdit: () -> Unit = {},
    onDelete: () -> Unit = {},
    onJumpToReply: () -> Unit = {},
) {
    val isOutgoing = message.isOutgoing
    val shape = RoundedCornerShape(10.dp)
    // iOS `CTMessageBubbleTheme`: outgoing is the dark-grey `outMsgBg` (light 0xE9E9E9) with
    // `outMsgText`, no stroke; only the text colour differs from incoming.
    val backgroundColor = if (isOutgoing) CTColor.outMsgBg else CTColor.bgMsg
    val contentColor = if (isOutgoing) CTColor.outMsgText else CTColor.text
    val metaColor = CTColor.textDim
    val quoteColor = CTColor.textDim
    val quoteBar = CTColor.accent

    // iOS `MessageBubbleRegularView`: the bubble at most 70 % of the row (and 360), 12/8 padding,
    // 15pt text; the meta line — status, edited, time — sits under the bubble, and only under the
    // last bubble of a group, in 10pt.
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(top = 2.dp, bottom = if (isLastInGroup) 8.dp else 2.dp),
    ) {
        val maxBubble = minOf(360.dp, maxWidth * 0.7f)
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = if (isOutgoing) Alignment.TopEnd else Alignment.TopStart) {
        Column(horizontalAlignment = if (isOutgoing) Alignment.End else Alignment.Start) {
            val album = (message.media as? MessageMedia.Album)?.takeUnless { it.isFiles }
            if (album != null) {
                // iOS: photos stand on their own, no bubble; the quote above, the caption below.
                var viewing by remember { mutableStateOf<Int?>(null) }
                if (replyLabel != null) {
                    Box(Modifier.widthIn(max = maxBubble)) {
                        ReplyQuoteStrip(replyLabel, quoteBar, quoteColor, onJumpToReply, onLongPress)
                    }
                }
                MediaAlbumView(album = album, onOpen = { viewing = it }, onLongPress = onLongPress)
                if (message.body.isNotBlank()) {
                    Text(
                        text = message.body,
                        style = ctMessage(12),
                        color = CTColor.text,
                        modifier = Modifier.widthIn(max = 260.dp).padding(top = 2.dp),
                    )
                }
                viewing?.let { MediaViewer(album, it, onDismiss = { viewing = null }) }
            } else Column(
                modifier = Modifier
                    .widthIn(max = maxBubble)
                    .background(backgroundColor, shape)
                    .then(
                        if (!isOutgoing) {
                            Modifier.border(width = 0.5.dp, color = CTColor.noise, shape = shape)
                        } else {
                            Modifier
                        }
                    )
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                        onLongClick = onLongPress,
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                if (replyLabel != null) {
                    ReplyQuoteStrip(
                        label = replyLabel,
                        barColor = quoteBar,
                        textColor = quoteColor,
                        onJump = onJumpToReply,
                        onLongPress = onLongPress,
                    )
                }
                mediaLine(message.media)?.let { line ->
                    Text(text = line, style = ctRegular(13), color = contentColor)
                }
                if (message.body.isNotBlank() || message.media == null) {
                    Text(
                        text = message.body,
                        style = ctMessage(15),
                        color = contentColor,
                    )
                }
            }
            if (isLastInGroup) {
                Row(
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isOutgoing) DeliveryStatusIcon(status = message.deliveryStatus)
                    if (message.isEdited) {
                        Text(text = stringResource(R.string.edited), style = ctRegular(10), color = metaColor)
                    }
                    Text(
                        text = formatMessageTime(message.timestamp),
                        style = ctRegular(10),
                        color = metaColor,
                    )
                }
            }
        }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = onDismissMenu,
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.reply), style = ctRegular(14), color = CTColor.text) },
                    leadingIcon = {
                        Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = null, tint = CTColor.text)
                    },
                    onClick = onReply,
                )
                if (message.isOutgoing && message.body.isNotBlank() && message.media == null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.edit_message), style = ctRegular(14), color = CTColor.text) },
                        leadingIcon = {
                            Icon(Icons.Filled.Edit, contentDescription = null, tint = CTColor.text)
                        },
                        onClick = onEdit,
                    )
                }
                if (message.body.isNotBlank()) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.copy), style = ctRegular(14), color = CTColor.text) },
                        leadingIcon = {
                            Icon(Icons.Filled.ContentCopy, contentDescription = null, tint = CTColor.text)
                        },
                        onClick = onCopy,
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.delete), style = ctRegular(14), color = CTColor.danger) },
                    leadingIcon = {
                        Icon(Icons.Filled.Delete, contentDescription = null, tint = CTColor.danger)
                    },
                    onClick = onDelete,
                )
            }
        }
    }
}

/**
 * Files and voice notes until they have bubbles of their own: what the message holds, so it is not
 * a blank bubble. Opening a file and playing a voice note come next.
 */
@Composable
private fun mediaLine(media: MessageMedia?): String? = when (media) {
    null -> null
    is MessageMedia.Voice -> stringResource(R.string.voice_message) +
        (media.audio.durationMs?.let { " · %d:%02d".format(it / 60_000, it / 1000 % 60) } ?: "")
    is MessageMedia.Album -> media.items.map { it.filename ?: stringResource(R.string.file_attachment) }.joinToString("\n")
}

/** Canon: iOS `MessageBubbleReplyPreview` — accent rule and up to two lines. Tap jumps. */
@Composable
private fun ReplyQuoteStrip(
    label: String,
    barColor: Color,
    textColor: Color,
    onJump: () -> Unit,
    onLongPress: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onJump,
                onLongClick = onLongPress,
                onClickLabel = stringResource(R.string.jump_to_replied_message),
            )
            .padding(bottom = Spacing.compact),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(
            Modifier
                .width(2.dp)
                .height(28.dp)
                .background(barColor),
        )
        Text(
            text = label,
            style = ctRegular(12),
            color = textColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = Spacing.compact),
        )
    }
}

/**
 * iOS `deliveryStatusView`: sending — an empty circle; sent — a filled one; delivered — a green
 * check in a circle (read renders the same: iOS has no separate read mark); failed — danger.
 */
@Composable
private fun DeliveryStatusIcon(status: DeliveryStatus) {
    val (icon, color) = when (status) {
        DeliveryStatus.SENDING -> Icons.Outlined.Circle to CTColor.textDim
        DeliveryStatus.SENT -> Icons.Filled.Circle to CTColor.textDim
        DeliveryStatus.DELIVERED, DeliveryStatus.READ -> Icons.Outlined.CheckCircle to SYSTEM_GREEN
        DeliveryStatus.FAILED -> Icons.Filled.Error to CTColor.danger
    }
    Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(11.dp))
}

/** SwiftUI `.green` (dark variant 0x30D158) — iOS's "delivered" colour. */
private val SYSTEM_GREEN = Color(0xFF30D158)

private fun formatMessageTime(timestamp: Long): String {
    val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
    return formatter.format(java.util.Date(timestamp))
}

@Preview(backgroundColor = 0xFF090909, showBackground = true, widthDp = 360)
@Composable
private fun MessageBubblePreview() {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(vertical = 8.dp),
    ) {
        MessageBubble(
            message = Message(
                id = "m1",
                chatId = "c1",
                body = "hey, are we still on for tonight?",
                isOutgoing = false,
                timestamp = System.currentTimeMillis(),
            ),
        )
        MessageBubble(
            message = Message(
                id = "m2",
                chatId = "c1",
                body = "yeah, see you at 8.",
                isOutgoing = true,
                deliveryStatus = DeliveryStatus.READ,
                timestamp = System.currentTimeMillis(),
            ),
        )
        MessageBubble(
            message = Message(
                id = "m3",
                chatId = "c1",
                body = "This one failed to send.",
                isOutgoing = true,
                deliveryStatus = DeliveryStatus.FAILED,
                timestamp = System.currentTimeMillis(),
            ),
        )
    }
}
