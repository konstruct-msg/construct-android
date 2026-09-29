package com.construct.messenger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.ArrowCircleUp
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.construct.messenger.R
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTLayout
import com.construct.messenger.ui.theme.CornerRadius
import com.construct.messenger.ui.theme.HairlineBorder
import com.construct.messenger.ui.theme.Spacing
import com.construct.messenger.ui.theme.ctMessage
import com.construct.messenger.ui.theme.ctRegular

/**
 * Chat composer: optional reply bar, text field, send.
 * Send is visible only when the field is not empty.
 *
 * **Canon:** iOS `MessageInputView` + `MessageReplyBar` / `ANDROID_ONBOARDING.md` §5.4.
 *
 * @param replyPreview One line of the message being quoted. Null hides the bar.
 * @param editingPreview One line of the message being edited. Takes the bar over a reply.
 */
@Composable
fun MessageInputView(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    replyPreview: String? = null,
    onCancelReply: () -> Unit = {},
    editingPreview: String? = null,
    onCancelEdit: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(CTColor.bg),
    ) {
        when {
            editingPreview != null -> EditComposerBar(preview = editingPreview, onCancel = onCancelEdit)
            replyPreview != null -> ReplyComposerBar(preview = replyPreview, onCancel = onCancelReply)
        }
        // iOS `MessageInputTextBar`: a glass capsule (44 high) holding the text — message face, 15 —
        // with the send button inside at the trailing edge once there is something to send. iOS's
        // attach "+" and mic are not here: Android has no attachments or voice messages yet.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = CTLayout.edgePad, vertical = 4.dp)
                .heightIn(min = 44.dp)
                .glassCapsule()
                .padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val textStyle = ctMessage(15).copy(color = CTColor.text)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = textStyle,
                cursorBrush = SolidColor(CTColor.accent),
                maxLines = 8,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 11.dp),
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty()) {
                            Text(
                                text = stringResource(R.string.chat_input_placeholder),
                                style = textStyle,
                                color = CTColor.textDim,
                            )
                        }
                        inner()
                    }
                },
            )
            if (value.isNotBlank()) {
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Filled.ArrowCircleUp,
                    contentDescription = stringResource(R.string.chat_send),
                    tint = if (enabled) CTColor.accent else CTColor.textDim,
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable(enabled = enabled, onClick = onSend),
                )
            }
        }
    }
}

/** Canon: iOS `MessageEditBar` — accent label, one line of the original, a cancel glyph. */
@Composable
private fun EditComposerBar(preview: String, onCancel: () -> Unit) {
    ComposerAuxBar(
        title = stringResource(R.string.editing_message),
        titleColor = CTColor.accent,
        preview = preview,
        onCancel = onCancel,
    )
}

/** Canon: iOS `MessageReplyBar` — accent rule, "Reply to:", one line, a cancel glyph. */
@Composable
private fun ReplyComposerBar(preview: String, onCancel: () -> Unit) {
    ComposerAuxBar(
        title = stringResource(R.string.reply_to_colon),
        titleColor = CTColor.textDim,
        preview = preview,
        onCancel = onCancel,
    )
}

@Composable
private fun ComposerAuxBar(
    title: String,
    titleColor: Color,
    preview: String,
    onCancel: () -> Unit,
) {
    val shape = RoundedCornerShape(CornerRadius.small)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = CTLayout.edgePad)
            .padding(top = Spacing.small)
            .clip(shape)
            .background(CTColor.bgMsg)
            .border(HairlineBorder, CTColor.noise, shape)
            .padding(horizontal = Spacing.small, vertical = Spacing.compact),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(
            Modifier
                .width(2.dp)
                .height(32.dp)
                .background(CTColor.accent),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = Spacing.small),
        ) {
            Text(
                text = title,
                style = ctRegular(11),
                color = titleColor,
            )
            Text(
                text = preview,
                style = ctRegular(13),
                color = CTColor.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = Icons.Filled.Cancel,
            contentDescription = stringResource(R.string.close),
            tint = CTColor.textDim,
            modifier = Modifier
                .size(32.dp)
                .clickable(onClick = onCancel),
        )
    }
}

@Preview
@Composable
private fun MessageInputViewPreview() {
    MessageInputView(value = "hello", onValueChange = {}, onSend = {})
}

@Preview(backgroundColor = 0xFF090909, showBackground = true, widthDp = 360)
@Composable
private fun MessageInputViewReplyPreview() {
    MessageInputView(
        value = "",
        onValueChange = {},
        onSend = {},
        replyPreview = "hey, are we still on for tonight?",
        onCancelReply = {},
    )
}
