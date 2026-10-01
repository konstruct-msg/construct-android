package com.construct.messenger.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.construct.messenger.ui.components.glassCapsule
import com.construct.messenger.ui.theme.CTLayout
import com.construct.messenger.ui.theme.ctBold
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import com.construct.messenger.ui.theme.ctRegular
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import com.construct.messenger.media.VoiceRecorder
import com.construct.messenger.ui.components.VoiceComposerBar
import com.construct.messenger.ui.components.VoicePlayback
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
import com.construct.messenger.ui.components.MessageBubble
import com.construct.messenger.ui.components.MessageInputView
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.viewmodel.ChatViewModel

@Composable
fun ChatScreen(
    onNavigateBack: () -> Unit,
    onOpenSafetyNumbers: () -> Unit,
    onOpenProfile: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val recording by viewModel.recording.collectAsStateWithLifecycle()
    val playing by viewModel.playing.collectAsStateWithLifecycle()
    val voiceLoading by viewModel.voiceLoading.collectAsStateWithLifecycle()
    val voiceUnavailable by viewModel.voiceUnavailable.collectAsStateWithLifecycle()
    val fileLoading by viewModel.fileLoading.collectAsStateWithLifecycle()
    val fileUnavailable by viewModel.fileUnavailable.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val micDenied = stringResource(R.string.voice_mic_denied)
    val noApp = stringResource(R.string.no_app_to_open)
    val tooLarge = stringResource(R.string.attachment_too_large)
    val unreadable = stringResource(R.string.attachment_unreadable)
    LaunchedEffect(Unit) {
        viewModel.attachmentProblem.collect { problem ->
            val text = if (problem == ChatViewModel.AttachmentProblem.TOO_LARGE) tooLarge else unreadable
            android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_LONG).show()
        }
    }
    LaunchedEffect(Unit) {
        viewModel.openFile.collect { open ->
            val view = android.content.Intent(android.content.Intent.ACTION_VIEW)
                .setDataAndType(open.uri, open.mime)
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try {
                context.startActivity(android.content.Intent.createChooser(view, null))
            } catch (e: android.content.ActivityNotFoundException) {
                android.widget.Toast.makeText(context, noApp, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
    fun startRecording() {
        if (!viewModel.startRecording()) {
            android.widget.Toast.makeText(context, micDenied, android.widget.Toast.LENGTH_LONG).show()
        }
    }
    // Asked for the first time the microphone is tapped, never before (AGENTS.md: permissions).
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording() else android.widget.Toast.makeText(context, micDenied, android.widget.Toast.LENGTH_LONG).show()
    }
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    var menuMessageId by remember { mutableStateOf<String?>(null) }
    var jumpToId by remember { mutableStateOf<String?>(null) }
    var reactingTo by remember { mutableStateOf<Message?>(null) }
    val reactionFailed = stringResource(R.string.reaction_failed)
    LaunchedEffect(Unit) {
        viewModel.reactionFailed.collect {
            android.widget.Toast.makeText(context, reactionFailed, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

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
        ChatNavBar(title = uiState.title, onBack = onNavigateBack, onOpenProfile = onOpenProfile)

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
            itemsIndexed(uiState.messages, key = { _, message -> message.id }) { index, message ->
                val voice = message.media as? com.construct.messenger.data.model.MessageMedia.Voice
                val voiceId = voice?.audio?.mediaId
                MessageBubble(
                    voicePlayback = if (voice == null) VoicePlayback() else VoicePlayback(
                        progress = playing?.takeIf { it.mediaId == voiceId }?.progress,
                        paused = playing?.takeIf { it.mediaId == voiceId }?.paused ?: false,
                        playingDurationMs = playing?.takeIf { it.mediaId == voiceId }?.durationMs ?: 0,
                        loading = voiceId in voiceLoading,
                        unavailable = voiceId in voiceUnavailable,
                        uploading = voiceId!!.startsWith(com.construct.messenger.util.MediaWire.LOCAL_PREFIX),
                    ),
                    onToggleVoice = { voice?.let(viewModel::toggleVoice) },
                    fileLoading = fileLoading,
                    fileUnavailable = fileUnavailable,
                    onOpenFile = viewModel::openFile,
                    loadMedia = viewModel::mediaBytes,
                    message = message,
                    isLastInGroup = isLastInGroup(index, uiState.messages),
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
                    onReact = { emoji ->
                        viewModel.react(message, emoji)
                        menuMessageId = null
                    },
                    onPickMoreReactions = {
                        reactingTo = message
                        menuMessageId = null
                    },
                )
            }
        }

        reactingTo?.let { target ->
            com.construct.messenger.ui.components.ReactionPickerSheet(
                onPick = { viewModel.react(target, it) },
                onDismiss = { reactingTo = null },
            )
        }

        // The system photo picker: no permission, nothing but what the user picks is readable.
        val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) {
            viewModel.attach(it)
        }
        // The system document picker: any file, nothing else readable.
        val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
            viewModel.attachFiles(it)
        }
        var attachMenuOpen by remember { mutableStateOf(false) }
        MessageInputView(
            onMic = if (uiState.editingOriginal == null) {
                {
                    val granted = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (granted) startRecording() else askMic.launch(android.Manifest.permission.RECORD_AUDIO)
                }
            } else {
                null
            },
            voiceBar = when (val r = recording) {
                is VoiceRecorder.State.Recording -> {
                    { VoiceComposerBar(true, r.durationMs, r.recent, viewModel::cancelRecording, viewModel::stopRecording) }
                }
                is VoiceRecorder.State.Recorded -> {
                    { VoiceComposerBar(false, r.durationMs, r.waveform, viewModel::cancelRecording, viewModel::sendRecording) }
                }
                VoiceRecorder.State.Idle -> null
            },
            attachments = uiState.attachments,
            onAttach = if (uiState.editingOriginal == null) {
                { attachMenuOpen = true }
            } else {
                null
            },
            attachMenu = {
                // iOS `MediaPickerSheet` has Gallery and Files tabs; here the two are a menu.
                DropdownMenu(expanded = attachMenuOpen, onDismissRequest = { attachMenuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.attach_photo_video), style = ctRegular(14), color = CTColor.text) },
                        leadingIcon = { Icon(Icons.Filled.Image, null, tint = CTColor.text) },
                        onClick = {
                            attachMenuOpen = false
                            pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.attach_file), style = ctRegular(14), color = CTColor.text) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null, tint = CTColor.text) },
                        onClick = {
                            attachMenuOpen = false
                            pickFiles.launch(arrayOf("*/*"))
                        },
                    )
                }
            },
            files = uiState.files.map { it.uri to it.name },
            onRemoveAttachment = viewModel::removeAttachment,
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

/**
 * iOS `Message.isLastInGroup`: a group ends where the sender changes or the next message comes
 * more than five minutes later. Only a group's last bubble carries the time and status.
 */
private fun isLastInGroup(index: Int, messages: List<Message>): Boolean {
    val next = messages.getOrNull(index + 1) ?: return true
    val current = messages[index]
    return current.isOutgoing != next.isOutgoing || next.timestamp - current.timestamp > 5 * 60 * 1000
}

/**
 * iOS `ChatNavBarView`: a floating glass capsule — back disc, the name upper-cased and tracked
 * (tapping it opens the profile). iOS's call and search buttons are not here: Android has neither
 * yet, and a button that does nothing is worse than none.
 */
@Composable
private fun ChatNavBar(title: String, onBack: () -> Unit, onOpenProfile: () -> Unit) {
    Row(
        modifier = Modifier
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .fillMaxWidth()
            .height(CTLayout.navBarHeight)
            .glassCapsule()
            .padding(horizontal = CTLayout.edgePad),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(CTLayout.navIconSizeLg)
                .clip(CircleShape)
                .background(CTColor.accent)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = stringResource(R.string.back),
                tint = CTColor.bg,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(CTLayout.inlinePad))
        Text(
            text = title.uppercase(),
            style = ctBold(14),
            color = CTColor.text,
            letterSpacing = 4.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onOpenProfile)
                .padding(vertical = 10.dp),
        )
    }
}
