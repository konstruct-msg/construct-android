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
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.Spacing
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
 * Long-press opens Reply and Copy. [replyLabel] is the quoted message's one or two
 * lines; null means this message is not a reply. The quote strip jumps to the original.
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
    onJumpToReply: () -> Unit = {},
) {
    val isOutgoing = message.isOutgoing
    val shape = RoundedCornerShape(10.dp)
    val backgroundColor = if (isOutgoing) CTColor.accent else CTColor.bgMsg
    val contentColor = if (isOutgoing) CTColor.outMsgText else CTColor.text
    val metaColor = if (isOutgoing) CTColor.outMsgText.copy(alpha = 0.7f) else CTColor.textDim
    val quoteColor = if (isOutgoing) CTColor.outMsgText.copy(alpha = 0.85f) else CTColor.textDim
    val quoteBar = if (isOutgoing) CTColor.outMsgText.copy(alpha = 0.85f) else CTColor.accent

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = if (isOutgoing) Arrangement.End else Arrangement.Start,
    ) {
        Box {
            Column(
                modifier = Modifier
                    .widthIn(max = 280.dp)
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
                    .padding(horizontal = 10.dp, vertical = 8.dp),
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
                Text(
                    text = message.body,
                    style = ctRegular(14),
                    color = contentColor,
                )
            Row(
                modifier = Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = formatMessageTime(message.timestamp),
                    style = ctRegular(11),
                    color = metaColor,
                    textAlign = TextAlign.End,
                )
                if (isOutgoing) {
                    Spacer(Modifier.width(4.dp))
                    DeliveryStatusIcon(status = message.deliveryStatus, tint = metaColor)
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
                if (message.body.isNotBlank()) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.copy), style = ctRegular(14), color = CTColor.text) },
                        leadingIcon = {
                            Icon(Icons.Filled.ContentCopy, contentDescription = null, tint = CTColor.text)
                        },
                        onClick = onCopy,
                    )
                }
            }
        }
    }
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

@Composable
private fun DeliveryStatusIcon(status: DeliveryStatus, tint: Color) {
    val (icon, color) = when (status) {
        DeliveryStatus.SENDING -> Icons.Default.AccessTime to tint
        DeliveryStatus.SENT -> Icons.Default.Done to tint
        DeliveryStatus.DELIVERED -> Icons.Default.DoneAll to tint
        DeliveryStatus.READ -> Icons.Default.DoneAll to CTColor.accentDim
        DeliveryStatus.FAILED -> Icons.Default.Error to CTColor.danger
    }

    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = color,
        modifier = Modifier.size(14.dp),
    )
}

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
