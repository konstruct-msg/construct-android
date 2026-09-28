package com.construct.messenger.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.construct.messenger.R
import com.construct.messenger.data.model.Message
import com.construct.messenger.ui.components.CTNavBar
import com.construct.messenger.ui.components.MessageBubble
import com.construct.messenger.ui.components.MessageInputView
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.viewmodel.ChatViewModel

@Composable
fun ChatScreen(
    onNavigateBack: () -> Unit,
    onOpenSafetyNumbers: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    var menuMessageId by remember { mutableStateOf<String?>(null) }
    var jumpToId by remember { mutableStateOf<String?>(null) }

    // Visible means started, not merely composed: a chat left open behind the home screen is
    // not being read (iOS learned this the hard way — see ChatPresence).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.onShown()
                Lifecycle.Event.ON_STOP -> viewModel.onHidden()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.onHidden()
        }
    }

    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty() && jumpToId == null) {
            listState.scrollToItem(uiState.messages.lastIndex)
        }
    }

    LaunchedEffect(jumpToId) {
        val id = jumpToId ?: return@LaunchedEffect
        val index = uiState.messages.indexOfFirst { it.id.equals(id, ignoreCase = true) }
        if (index >= 0) listState.animateScrollToItem(index)
        jumpToId = null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CTColor.bg)
            // Edge-to-edge (MainActivity): without these the title sits under the status bar and
            // the composer under the opaque navigation bar. navigationBars before imePadding,
            // which then pads only what the keyboard adds beyond the bar.
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        CTNavBar(
            title = uiState.title,
            showBack = true,
            onBack = onNavigateBack,
            trailingIcon = Icons.Default.Shield,
            trailingColor = CTColor.textDim,
            onTrailingAction = onOpenSafetyNumbers,
        )

        SecurityNoticeBanner(
            notice = uiState.securityNotice,
            contactName = uiState.contactName,
            onVerify = onOpenSafetyNumbers,
            onAcknowledge = viewModel::acknowledgeSecurityNotice,
        )

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            state = listState,
        ) {
            items(uiState.messages, key = { it.id }) { message ->
                MessageBubble(
                    message = message,
                    replyLabel = replyLabel(message, uiState.messages),
                    onLongPress = { menuMessageId = message.id },
                    menuExpanded = menuMessageId == message.id,
                    onDismissMenu = { menuMessageId = null },
                    onReply = {
                        viewModel.startReply(message)
                        menuMessageId = null
                    },
                    onCopy = {
                        clipboard.setText(AnnotatedString(message.body))
                        menuMessageId = null
                    },
                    onEdit = {
                        viewModel.startEdit(message)
                        menuMessageId = null
                    },
                    onDelete = {
                        viewModel.delete(message)
                        menuMessageId = null
                    },
                    onJumpToReply = { jumpToId = message.replyToId },
                )
            }
        }

        MessageInputView(
            value = uiState.draft,
            onValueChange = viewModel::onDraftChange,
            onSend = viewModel::send,
            enabled = !uiState.sending,
            replyPreview = uiState.replyingTo?.let { reply ->
                reply.preview.ifBlank { quoteFallback(reply.mediaType) }
            },
            onCancelReply = viewModel::cancelReply,
            editingPreview = uiState.editingOriginal,
            onCancelEdit = viewModel::cancelEdit,
        )
    }
}

/**
 * What the quote strip says. The stored preview wins; otherwise the quoted row's own
 * text, if it is in this transcript; otherwise the media kind iOS sent with the quote.
 */
@Composable
private fun replyLabel(message: Message, transcript: List<Message>): String? {
    val quotedId = message.replyToId ?: return null
    val preview = message.replyPreview?.takeIf { it.isNotBlank() }
    if (preview != null) return preview
    val local = transcript.firstOrNull { it.id.equals(quotedId, ignoreCase = true) }?.body
    if (!local.isNullOrBlank()) return local
    return quoteFallback(message.replyMediaType)
}

@Composable
private fun quoteFallback(mediaType: String?): String = when (mediaType) {
    "MEDIA_TYPE_IMAGE", "MEDIA_TYPE_ANIMATED" -> stringResource(R.string.photo)
    "MEDIA_TYPE_VIDEO" -> stringResource(R.string.video)
    "MEDIA_TYPE_AUDIO" -> stringResource(R.string.voice_message)
    "MEDIA_TYPE_FILE" -> stringResource(R.string.file_attachment)
    "MEDIA_TYPE_STICKER" -> stringResource(R.string.sticker)
    else -> stringResource(R.string.message_unavailable)
}
