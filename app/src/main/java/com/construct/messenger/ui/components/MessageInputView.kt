package com.construct.messenger.ui.components

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.ArrowCircleUp
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.construct.messenger.R
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTFont
import com.construct.messenger.ui.theme.CTIcon
import com.construct.messenger.ui.theme.CTLayout
import com.construct.messenger.ui.theme.CTSpace
import com.construct.messenger.ui.theme.CornerRadius
import com.construct.messenger.ui.theme.HairlineBorder

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
    attachments: List<Uri> = emptyList(),
    files: List<Pair<Uri, String>> = emptyList(),
    onAttach: (() -> Unit)? = null,
    attachMenu: (@Composable () -> Unit)? = null,
    onRemoveAttachment: (Uri) -> Unit = {},
    onMic: (() -> Unit)? = null,
    /** A long press on the mic offers the camera; null leaves the mic a plain button. */
    onVideoNote: (() -> Unit)? = null,
    voiceBar: (@Composable () -> Unit)? = null,
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
        if (voiceBar != null) {
            // iOS: the recording bar takes the row's place; nothing else of the composer shows.
            voiceBar()
            return@Column
        }
        if (attachments.isNotEmpty() || files.isNotEmpty()) AttachmentStrip(attachments, files, onRemoveAttachment)
        // iOS `MessageInputTextBar`: the attach "+" in a glass circle (44), then a glass capsule
        // (44 high) holding the text — message face, 15 — with the send button inside at the
        // trailing edge once there is something to send, the microphone while there is not.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = CTLayout.edgePad, vertical = CTSpace.xs),
            verticalAlignment = Alignment.Bottom,
        ) {
        if (onAttach != null) {
            Box(
                modifier = Modifier
                    .size(CTLayout.hitTarget)
                    .glassCapsule()
                    .clickable(onClick = onAttach),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.AddCircleOutline,
                    contentDescription = stringResource(R.string.attach),
                    tint = CTColor.textDim,
                    modifier = Modifier.size(CTIcon.control),
                )
                attachMenu?.invoke()
            }
            Spacer(Modifier.width(CTSpace.s))
        }
        Row(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 44.dp)
                // iOS `InputBar.cornerRadius`: half of one line's height, kept as the text grows.
                .glassCapsule(cornerRadius = 22.dp)
                .padding(start = CTSpace.l, end = CTSpace.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val textStyle = CTFont.message(15).copy(color = CTColor.text)
            // The field keeps its own selection. Text set from outside — a message taken up for
            // editing, a cleared draft — puts the cursor after it, where typing goes on; the String
            // overload left it wherever it was, at the start of the message being edited.
            var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
            if (field.text != value) field = TextFieldValue(value, TextRange(value.length))
            val focus = remember { FocusRequester() }
            LaunchedEffect(editingPreview != null) { if (editingPreview != null) runCatching { focus.requestFocus() } }
            BasicTextField(
                value = field,
                onValueChange = {
                    field = it
                    if (it.text != value) onValueChange(it.text)
                },
                textStyle = textStyle,
                cursorBrush = SolidColor(CTColor.accent),
                maxLines = 8,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focus)
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
            if (value.isNotBlank() || attachments.isNotEmpty() || files.isNotEmpty()) {
                Spacer(Modifier.width(CTSpace.xs))
                Icon(
                    imageVector = Icons.Filled.ArrowCircleUp,
                    contentDescription = stringResource(R.string.chat_send),
                    tint = if (enabled) CTColor.accent else CTColor.textDim,
                    modifier = Modifier
                        .size(CTIcon.overlay)
                        .clip(CircleShape)
                        .clickable(enabled = enabled, onClick = onSend),
                )
            } else if (onMic != null && onVideoNote != null) {
                Spacer(Modifier.width(CTSpace.xs))
                MicModeButton(size = 28.dp, onVoice = onMic, onVideoNote = onVideoNote)
            } else if (onMic != null) {
                Spacer(Modifier.width(CTSpace.xs))
                Icon(
                    imageVector = Icons.Filled.Mic,
                    contentDescription = stringResource(R.string.voice_record),
                    tint = CTColor.textDim,
                    modifier = Modifier
                        .size(CTIcon.overlay)
                        .clip(CircleShape)
                        .clickable(onClick = onMic)
                        .padding(2.dp),
                )
            }
        }
        }
    }
}

/**
 * Canon: iOS `MessageAttachmentPreviews` — the picked photos above the field, each with a
 * remove button; they go as one album with the text as its caption.
 */
@Composable
private fun AttachmentStrip(uris: List<Uri>, files: List<Pair<Uri, String>>, onRemove: (Uri) -> Unit) {
    val shape = RoundedCornerShape(CornerRadius.small)
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = CTLayout.edgePad)
            .padding(top = CTSpace.s)
            .clip(shape)
            .background(CTColor.bgMsg)
            .border(HairlineBorder, CTColor.noise, shape),
        contentPadding = PaddingValues(CTSpace.s),
        horizontalArrangement = Arrangement.spacedBy(CTSpace.s),
    ) {
        // iOS `MessageAttachmentPreviews`: a file is a chip — icon, name, remove — not a tile.
        items(files, key = { it.first.toString() }) { (uri, name) ->
            Row(
                Modifier
                    .height(80.dp)
                    .clip(RoundedCornerShape(CornerRadius.small))
                    .background(CTColor.bg)
                    .padding(horizontal = CTSpace.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null, tint = CTColor.accent, modifier = Modifier.size(CTIcon.control))
                Spacer(Modifier.width(CTSpace.s))
                Text(
                    name,
                    style = CTFont.body,
                    color = CTColor.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 160.dp),
                )
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.remove),
                    tint = CTColor.textDim,
                    modifier = Modifier
                        .padding(start = CTSpace.s)
                        .clip(CircleShape)
                        .clickable { onRemove(uri) }
                        .padding(CTSpace.xs)
                        .size(CTIcon.nav),
                )
            }
        }
        items(uris, key = { it.toString() }) { uri ->
            Box(Modifier.size(80.dp)) {
                AsyncImage(
                    model = uri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(CornerRadius.small)),
                )
                Icon(
                    imageVector = Icons.Filled.Cancel,
                    contentDescription = stringResource(R.string.remove),
                    tint = CTColor.onMedia,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(CTSpace.xs)
                        .size(CTIcon.navLg)
                        .clip(CircleShape)
                        .background(CTColor.mediaScrim)
                        .clickable { onRemove(uri) },
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
            .padding(top = CTSpace.s)
            .clip(shape)
            .background(CTColor.bgMsg)
            .border(HairlineBorder, CTColor.noise, shape)
            .padding(horizontal = CTSpace.s, vertical = CTSpace.xs),
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
                .padding(horizontal = CTSpace.s),
        ) {
            Text(
                text = title,
                style = CTFont.micro,
                color = titleColor,
            )
            Text(
                text = preview,
                style = CTFont.secondary,
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
                .size(CTIcon.overlay)
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
