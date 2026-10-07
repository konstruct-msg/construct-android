@file:OptIn(ExperimentalFoundationApi::class)

package com.construct.messenger.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.construct.messenger.R
import com.construct.messenger.data.model.DeliveryStatus
import com.construct.messenger.data.model.Message
import com.construct.messenger.data.model.MessageMedia
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTFont
import com.construct.messenger.ui.theme.CTIcon
import com.construct.messenger.ui.theme.CTSpace
import com.construct.messenger.util.ReactionRules
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Simple E2EE message bubble.
 *
 * **Canon:** iOS `MessageBubbleRegularView` + `ANDROID_ONBOARDING.md` §5.4.
 * - Incoming: `CTColor.bgMsg` background + 0.5dp `CTColor.noise` border.
 * - Outgoing: `CTColor.accent` background.
 * - 10dp rounded corners.
 *
 * Long-press opens the iOS menu in its order: the reaction quick set, Reply, Quote & Reply,
 * Edit (our text or photo caption), Copy, Select Messages, Delete (this phone only), Retry (ours
 * that failed). A double tap is ❤️, a leftward swipe replies. [replyLabel] is the quoted message's one or two lines; null
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
    onReact: (String) -> Unit = {},
    /** The emoji heading the menu, in display order; the user's own once learned. */
    reactionRow: List<String> = ReactionRules.QUICK_SET,
    onPickMoreReactions: () -> Unit = {},
    onQuoteReply: () -> Unit = {},
    onSelectMessages: () -> Unit = {},
    onRetry: () -> Unit = {},
    onSaveMedia: (com.construct.messenger.data.model.MediaItem) -> Unit = {},
    onShareMedia: (com.construct.messenger.data.model.MediaItem) -> Unit = {},
    stickerFile: (com.construct.messenger.stickers.StickerReference) -> java.io.File? = { null },
    onStickerMissing: (com.construct.messenger.stickers.StickerReference) -> Unit = {},
    /** Null outside selection mode; otherwise whether this message is among the chosen. */
    selected: Boolean? = null,
    onToggleSelected: () -> Unit = {},
    voicePlayback: VoicePlayback = VoicePlayback(),
    onToggleVoice: () -> Unit = {},
    fileLoading: Set<String> = emptySet(),
    fileUnavailable: Set<String> = emptySet(),
    onOpenFile: (com.construct.messenger.data.model.MediaItem) -> Unit = {},
    loadMedia: suspend (com.construct.messenger.data.model.MediaItem) -> ByteArray = { error("no loader") },
    videoNote: VideoNoteUi = VideoNoteUi(),
    videoNoteActions: VideoNoteActions = VideoNoteActions(),
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
    BubbleFrame(
        modifier = modifier,
        selected = selected,
        onToggleSelected = onToggleSelected,
        onSwipeReply = onReply,
    ) { swipe ->
    BoxWithConstraints(
        modifier = swipe
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(top = 2.dp, bottom = if (isLastInGroup) 8.dp else 2.dp),
    ) {
        val maxBubble = minOf(360.dp, maxWidth * 0.7f)
        // The whole row: this box's width plus its 12 dp padding on each side.
        val noteWidth = VideoNoteLayout.expandedWidth(maxWidth + 24.dp)
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = if (isOutgoing) Alignment.TopEnd else Alignment.TopStart) {
        Column(horizontalAlignment = if (isOutgoing) Alignment.End else Alignment.Start) {
            // iOS `badgeOverlap`: the chip hangs below the bubble on the corner away from the time,
            // and the row keeps room for it so it never sits on the next line.
            Box(
                modifier = Modifier.padding(bottom = if (message.reactions.isEmpty()) 0.dp else REACTION_OVERHANG),
                contentAlignment = if (isOutgoing) Alignment.BottomStart else Alignment.BottomEnd,
            ) {
            Column(horizontalAlignment = if (isOutgoing) Alignment.End else Alignment.Start) {
            val album = (message.media as? MessageMedia.Album)?.takeUnless { it.isFiles }
            val voice = message.media as? MessageMedia.Voice
            val files = (message.media as? MessageMedia.Album)?.takeIf { it.isFiles }
            val sticker = message.media as? MessageMedia.Sticker
            if (sticker != null) {
                // iOS: the sticker stands alone, no bubble; a reply to it shows above.
                StickerBubble(
                    ref = sticker.ref,
                    file = stickerFile(sticker.ref),
                    onMissing = { onStickerMissing(sticker.ref) },
                    onLongPress = onLongPress,
                    onDoubleTap = { onReact(ReactionRules.LIKE) },
                )
            } else if (files != null) {
                if (replyLabel != null) {
                    Box(Modifier.widthIn(max = maxBubble)) {
                        ReplyQuoteStrip(replyLabel, quoteBar, quoteColor, onJumpToReply, onLongPress)
                    }
                }
                Box(
                    Modifier.combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                        onLongClick = onLongPress,
                        onDoubleClick = { onReact(ReactionRules.LIKE) },
                    ),
                ) {
                    FilesBubble(files, message.body, isOutgoing, maxBubble, fileLoading, fileUnavailable, onOpenFile, onLongPress)
                }
            } else if (voice != null) {
                VoiceBubble(
                    voice = voice,
                    outgoing = isOutgoing,
                    playback = voicePlayback,
                    maxWidth = maxBubble,
                    onToggle = onToggleVoice,
                    onLongPress = onLongPress,
                    onDoubleTap = { onReact(ReactionRules.LIKE) },
                )
            } else if (album != null) {
                // iOS: photos stand on their own, no bubble; the quote above, the caption below.
                var viewing by remember { mutableStateOf<Int?>(null) }
                if (replyLabel != null) {
                    Box(Modifier.widthIn(max = maxBubble)) {
                        ReplyQuoteStrip(replyLabel, quoteBar, quoteColor, onJumpToReply, onLongPress)
                    }
                }
                val note = album.videoNote
                if (note != null) {
                    VideoNoteBubble(
                        item = note,
                        load = loadMedia,
                        playback = videoNote,
                        actions = videoNoteActions,
                        expandedWidth = noteWidth,
                        onOpenFullScreen = { viewing = 0 },
                        onLongPress = onLongPress,
                        onDoubleTap = { onReact(ReactionRules.LIKE) },
                    )
                } else {
                    MediaAlbumView(
                        album = album,
                        onOpen = { viewing = it },
                        onLongPress = onLongPress,
                        onDoubleTap = { onReact(ReactionRules.LIKE) },
                    )
                }
                if (message.body.isNotBlank()) {
                    LinkedText(
                        text = message.body,
                        style = CTFont.message(12),
                        color = CTColor.text,
                        linkColor = CTColor.accent,
                        onLongPress = onLongPress,
                        onDoubleTap = { onReact(ReactionRules.LIKE) },
                        modifier = Modifier.widthIn(max = 260.dp).padding(top = 2.dp),
                    )
                }
                viewing?.let {
                    MediaViewer(
                        album, it,
                        onDismiss = { viewing = null },
                        loadVideo = loadMedia,
                        onSave = onSaveMedia,
                        onShare = onShareMedia,
                        // Full screen opens playing, with sound (iOS: the expanded note's button).
                        autoPlay = note != null,
                    )
                }
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
                        // iOS: a double tap is the like — the first of the quick set.
                        onDoubleClick = { onReact(ReactionRules.LIKE) },
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
                if (message.body.isNotBlank() || message.media == null) {
                    LinkedText(
                        text = message.body,
                        style = CTFont.message(15),
                        color = contentColor,
                        // iOS: links in the accent on an incoming bubble, in the text colour on ours.
                        linkColor = if (isOutgoing) contentColor else CTColor.accent,
                        onLongPress = onLongPress,
                        onDoubleTap = { onReact(ReactionRules.LIKE) },
                    )
                }
            }
            }
            ReactionBadgeRow(
                reactions = message.reactions,
                onTap = onReact,
                modifier = Modifier.offset(y = REACTION_OVERHANG),
            )
            }
            if (isLastInGroup) {
                Row(
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isOutgoing) DeliveryStatusIcon(status = message.deliveryStatus)
                    if (message.isEdited) {
                        Text(text = stringResource(R.string.edited), style = CTFont.micro, color = metaColor)
                    }
                    Text(
                        text = formatMessageTime(message.timestamp),
                        style = CTFont.micro,
                        color = metaColor,
                    )
                }
            }
        }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = onDismissMenu,
            ) {
                ReactionQuickRow(row = reactionRow, current = message.myReaction, onPick = onReact, onPickMore = onPickMoreReactions)
                HorizontalDivider(color = CTColor.noise, thickness = 0.5.dp)
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.reply), style = CTFont.ui(14), color = CTColor.text) },
                    leadingIcon = {
                        Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = null, tint = CTColor.text)
                    },
                    onClick = onReply,
                )
                if (message.body.isNotBlank()) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.quote_reply), style = CTFont.ui(14), color = CTColor.text) },
                        leadingIcon = { Icon(Icons.Filled.FormatQuote, contentDescription = null, tint = CTColor.text) },
                        onClick = onQuoteReply,
                    )
                }
                if (message.isEditable) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.edit_message), style = CTFont.ui(14), color = CTColor.text) },
                        leadingIcon = {
                            Icon(Icons.Filled.Edit, contentDescription = null, tint = CTColor.text)
                        },
                        onClick = onEdit,
                    )
                }
                if (message.body.isNotBlank()) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.copy), style = CTFont.ui(14), color = CTColor.text) },
                        leadingIcon = {
                            Icon(Icons.Filled.ContentCopy, contentDescription = null, tint = CTColor.text)
                        },
                        onClick = onCopy,
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.select_messages), style = CTFont.ui(14), color = CTColor.text) },
                    leadingIcon = { Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = CTColor.text) },
                    onClick = onSelectMessages,
                )
                HorizontalDivider(color = CTColor.noise, thickness = 0.5.dp)
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.delete), style = CTFont.ui(14), color = CTColor.danger) },
                    leadingIcon = {
                        Icon(Icons.Filled.Delete, contentDescription = null, tint = CTColor.danger)
                    },
                    onClick = onDelete,
                )
                if (message.isOutgoing && message.deliveryStatus == DeliveryStatus.FAILED) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.retry), style = CTFont.ui(14), color = CTColor.text) },
                        leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null, tint = CTColor.text) },
                        onClick = onRetry,
                    )
                }
            }
        }
    }
    }
}

/**
 * What surrounds a bubble: in selection mode a check at the leading edge and the whole row one
 * target that toggles it (iOS: a tap selects, the menu is off); otherwise the swipe that replies —
 * iOS `swipeToReplyGesture`, leftwards, the bubble following at half speed up to 60 and a release
 * past 40 committing, with the reply arrow fading in behind it.
 */
@Composable
private fun BubbleFrame(
    modifier: Modifier,
    selected: Boolean?,
    onToggleSelected: () -> Unit,
    onSwipeReply: () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    if (selected != null) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .background(if (selected) CTColor.accent.copy(alpha = 0.08f) else Color.Transparent)
                .clickable(onClick = onToggleSelected),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (selected) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                contentDescription = null,
                tint = if (selected) CTColor.accent else CTColor.textDim,
                modifier = Modifier.padding(start = 12.dp).size(CTIcon.nav),
            )
            Box(Modifier.weight(1f)) {
                content(Modifier)
                // Above the bubble's own gestures, so a tap anywhere toggles and nothing else.
                Box(Modifier.matchParentSize().clickable(onClick = onToggleSelected))
            }
        }
        return
    }
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    var travel by remember { mutableFloatStateOf(0f) }
    val offset = with(density) { minOf(travel * 0.5f, SWIPE_MAX.toPx()) }
    val commit = with(density) { SWIPE_COMMIT.toPx() }
    Box(
        modifier = modifier.pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragEnd = {
                    if (minOf(travel * 0.5f, SWIPE_MAX.toPx()) >= commit) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onSwipeReply()
                    }
                    travel = 0f
                },
                onDragCancel = { travel = 0f },
                onHorizontalDrag = { change, amount ->
                    travel = (travel - amount).coerceAtLeast(0f)
                    change.consume()
                },
            )
        },
    ) {
        content(Modifier.offset { IntOffset(-offset.roundToInt(), 0) })
        if (offset > with(density) { SWIPE_INDICATOR.toPx() }) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Reply,
                contentDescription = null,
                tint = CTColor.accent,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 8.dp)
                    .size(CTIcon.row)
                    .alpha((offset / commit).coerceIn(0f, 1f)),
            )
        }
    }
}

private val SWIPE_MAX = 60.dp
private val SWIPE_COMMIT = 40.dp
private val SWIPE_INDICATOR = 10.dp

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
            .padding(bottom = CTSpace.xs),
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
            style = CTFont.secondary,
            color = textColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = CTSpace.xs),
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
    Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(CTIcon.caption))
}

/** iOS `Reaction.badgeOverlap` — more than the bubble's padding and the last line's glyphs. */
private val REACTION_OVERHANG = 18.dp

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
