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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.construct.messenger.ui.theme.ctRegular

/**
 * Chat composer: optional reply bar, text field, send.
 * Send is visible only when the field is not empty.
 *
 * **Canon:** iOS `MessageInputView` + `MessageReplyBar` / `ANDROID_ONBOARDING.md` §5.4.
 *
 * @param replyPreview One line of the message being quoted. Null hides the bar.
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
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(CTColor.bg),
    ) {
        if (replyPreview != null) {
            ReplyComposerBar(preview = replyPreview, onCancel = onCancelReply)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = CTLayout.edgePad, vertical = Spacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CTTextField(
                placeholder = stringResource(R.string.chat_input_placeholder),
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
            )
            if (value.isNotBlank()) {
                Spacer(Modifier.width(Spacing.small))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(R.string.chat_send),
                    tint = if (enabled) CTColor.accent else CTColor.textDim,
                    modifier = Modifier
                        .size(CTLayout.navIconSizeLg)
                        .clickable(enabled = enabled, onClick = onSend),
                )
            }
        }
    }
}

/** Canon: iOS `MessageReplyBar` — accent rule, "Reply to:", one line, a cancel glyph. */
@Composable
private fun ReplyComposerBar(preview: String, onCancel: () -> Unit) {
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
                text = stringResource(R.string.reply_to_colon),
                style = ctRegular(11),
                color = CTColor.textDim,
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
