package com.construct.messenger.ui.screens.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.construct.messenger.R
import com.construct.messenger.data.model.Message
import com.construct.messenger.media.VoiceRecorder
import com.construct.messenger.ui.components.MessageBubble
import com.construct.messenger.ui.components.MessageInputView
import com.construct.messenger.ui.components.VideoNoteActions
import com.construct.messenger.ui.components.VideoNoteUi
import com.construct.messenger.ui.components.VoiceComposerBar
import com.construct.messenger.ui.components.VoicePlayback
import com.construct.messenger.ui.components.glassCapsule
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTFont
import com.construct.messenger.ui.theme.CTIcon
import com.construct.messenger.ui.theme.CTLayout
import com.construct.messenger.viewmodel.ChatViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    val videoNote by viewModel.videoNote.collectAsStateWithLifecycle()
    val reactionRow by viewModel.reactionRow.collectAsStateWithLifecycle()
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
    // A video note: the camera and the microphone, asked for the first time one is recorded.
    var recordingNote by remember { mutableStateOf(false) }
    val noteUnavailable = stringResource(R.string.video_note_camera_unavailable)
    val askNote = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) recordingNote = true
        else android.widget.Toast.makeText(context, noteUnavailable, android.widget.Toast.LENGTH_LONG).show()
    }
    fun startVideoNote() {
        val needed = arrayOf(android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO)
        val held = needed.all {
            androidx.core.content.ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (held) recordingNote = true else askNote.launch(needed)
    }
    val listState = rememberLazyListState()
    // iOS `ChatViewport.mode`: following the newest message, or reading history (TranscriptFollow).
    var following by remember { mutableStateOf(true) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val nearBottomSlack = with(androidx.compose.ui.platform.LocalDensity.current) { NEAR_BOTTOM.roundToPx() }
    LaunchedEffect(listState) {
        androidx.compose.runtime.snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            TranscriptFollow.nearBottom(last?.index, last?.let { it.offset + it.size } ?: 0, info.totalItemsCount, info.viewportEndOffset, nearBottomSlack) to
                listState.isScrollInProgress
        }.collect { (near, scrolling) -> following = TranscriptFollow.next(following, near, scrolling) }
    }
    // Sending is an explicit return to the newest message, as on iOS: whoever sent something
    // means to see it land, even from deep in history.
    fun followNext() {
        following = true
    }
    val clipboard = LocalClipboardManager.current
    var menuMessageId by remember { mutableStateOf<String?>(null) }
    var jumpToId by remember { mutableStateOf<String?>(null) }
    var reactingTo by remember { mutableStateOf<Message?>(null) }
    val stickersVm: com.construct.messenger.viewmodel.StickersViewModel = hiltViewModel()
    // Read so the transcript recomposes when a pack lands and an emoji becomes its picture.
    val stickerGeneration by stickersVm.generation.collectAsStateWithLifecycle()
    var pickingSticker by remember { mutableStateOf(false) }
    val stickerWord = stringResource(R.string.sticker)
    val saved = stringResource(R.string.media_saved)
    val saveFailed = stringResource(R.string.media_save_failed)
    LaunchedEffect(Unit) {
        viewModel.galleryResult.collect { ok ->
            android.widget.Toast.makeText(context, if (ok) saved else saveFailed, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    LaunchedEffect(Unit) {
        viewModel.shareFile.collect { file ->
            val send = android.content.Intent(android.content.Intent.ACTION_SEND)
                .setType(file.mime)
                .putExtra(android.content.Intent.EXTRA_STREAM, file.uri)
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(android.content.Intent.createChooser(send, null))
        }
    }
    var quoting by remember { mutableStateOf<Message?>(null) }
    // iOS `isEditMode`: a tap selects, the menu and the composer give way to the selection bar.
    var selectedIds by remember { mutableStateOf<Set<String>?>(null) }
    // iOS `isSearchActive`: search takes the nav bar's place and the transcript shows the matches.
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val visible = if (searching && query.isNotBlank()) {
        uiState.messages.filter { it.body.contains(query.trim(), ignoreCase = true) }
    } else {
        uiState.messages
    }
    androidx.activity.compose.BackHandler(enabled = selectedIds != null || searching) {
        if (selectedIds != null) selectedIds = null else {
            searching = false
            query = ""
        }
    }
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

    LaunchedEffect(visible.size) {
        if (following && visible.isNotEmpty() && jumpToId == null && !(searching && query.isNotBlank())) {
            listState.scrollToEnd(visible.lastIndex)
        }
    }

    // The keyboard (imePadding) and a growing composer shrink the viewport, and a list keeps its
    // top: the newest message went under the keyboard until the next send (testers, 2026-10-03).
    // While following, the tail stays at the bottom through every frame of the inset — iOS gets
    // this from `.defaultScrollAnchor(.bottom)`. Reading history is left where it is.
    val lastIndex by androidx.compose.runtime.rememberUpdatedState(visible.lastIndex)
    LaunchedEffect(listState) {
        androidx.compose.runtime.snapshotFlow { listState.layoutInfo.viewportSize.height }
            .collect { if (following && lastIndex >= 0) listState.scrollToEnd(lastIndex) }
    }

    // iOS scrolls to the first match as the query changes.
    LaunchedEffect(query) {
        if (searching && query.isNotBlank() && visible.isNotEmpty()) listState.scrollToItem(0)
    }

    // A video note expanding in place grows downward; bring the whole of it into view once it has
    // (iOS `ChatView` `.onChange(of: VideoNotePlayback.expanded)`).
    LaunchedEffect(videoNote.expanded) {
        val key = videoNote.expanded ?: return@LaunchedEffect
        delay(VIDEO_NOTE_EXPAND_MS)
        val index = visible.indexOfFirst { it.id == key.messageId }
        val info = listState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return@LaunchedEffect
        val viewportCenter = (info.viewportStartOffset + info.viewportEndOffset) / 2
        listState.animateScrollBy((item.offset + item.size / 2 - viewportCenter).toFloat())
    }

    LaunchedEffect(jumpToId) {
        val id = jumpToId ?: return@LaunchedEffect
        if (searching) {
            searching = false
            query = ""
        }
        val index = uiState.messages.indexOfFirst { it.id.equals(id, ignoreCase = true) }
        if (index >= 0) listState.animateScrollToItem(index)
        jumpToId = null
    }

    val actionPalette = rememberChatActionPaletteHost()
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
        if (searching) {
            ChatSearchBar(
                query = query,
                onQueryChange = { query = it },
                resultCount = visible.size,
                onClose = {
                    searching = false
                    query = ""
                },
            )
        } else {
            ChatNavBar(
                title = uiState.title,
                onBack = onNavigateBack,
                onOpenProfile = onOpenProfile,
                selecting = selectedIds != null,
                onDoneSelecting = { selectedIds = null },
                onSearch = { searching = true },
                onCall = com.construct.messenger.ui.screens.calls.rememberCallAction(uiState.contactId),
                onVideoCall = com.construct.messenger.ui.screens.calls.rememberCallAction(uiState.contactId, video = true),
                alerted = uiState.trustAlert != null,
                ktVerified = uiState.ktVerified,
                onVerify = onOpenSafetyNumbers,
                actionPalette = actionPalette,
            )
        }

        SecurityNoticeBanner(
            alert = uiState.trustAlert,
            contactName = uiState.contactName,
            onVerify = onOpenSafetyNumbers,
            onAcknowledge = viewModel::acknowledgeSecurityNotice,
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
        ) {
            itemsIndexed(visible, key = { _, message -> message.id }) { index, message ->
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
                    videoNote = videoNote.takeIf { it.expanded?.messageId == message.id }?.let {
                        VideoNoteUi(expanded = true, paused = it.paused, progress = it.progress, rate = it.rate)
                    } ?: VideoNoteUi(),
                    videoNoteActions = remember(message.id) {
                        val key = com.construct.messenger.media.VideoNotePlayback.Key(message.id)
                        VideoNoteActions(
                            tap = { viewModel.tapVideoNote(key, it) },
                            cycleRate = viewModel::cycleVideoNoteRate,
                            collapse = { viewModel.collapseVideoNote(key) },
                            attach = viewModel::attachVideoNote,
                            detach = viewModel::detachVideoNote,
                        )
                    },
                    fileLoading = fileLoading,
                    fileUnavailable = fileUnavailable,
                    onOpenFile = viewModel::openFile,
                    loadMedia = viewModel::mediaBytes,
                    message = message,
                    isLastInGroup = isLastInGroup(index, visible),
                    replyLabel = replyLabel(message, uiState.messages),
                    onLongPress = { menuMessageId = message.id },
                    menuExpanded = menuMessageId == message.id,
                    onDismissMenu = { menuMessageId = null },
                    onReply = {
                        val sticker = message.media as? com.construct.messenger.data.model.MessageMedia.Sticker
                        viewModel.startReply(message, sticker?.let { "${it.ref.emoji} $stickerWord" })
                        menuMessageId = null
                    },
                    stickerFile = { ref -> stickerGeneration.let { stickersVm.file(ref) } },
                    onStickerMissing = stickersVm::ensure,
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
                    reactionRow = reactionRow,
                    onPickMoreReactions = {
                        reactingTo = message
                        menuMessageId = null
                    },
                    onQuoteReply = {
                        quoting = message
                        menuMessageId = null
                    },
                    onSelectMessages = {
                        searching = false
                        query = ""
                        selectedIds = setOf(message.id)
                        menuMessageId = null
                    },
                    onSaveMedia = viewModel::saveToGallery,
                    onShareMedia = viewModel::share,
                    onRetry = {
                        viewModel.retry(message)
                        menuMessageId = null
                    },
                    selected = selectedIds?.let { message.id in it },
                    onToggleSelected = {
                        selectedIds = selectedIds?.let { if (message.id in it) it - message.id else it + message.id }
                    },
                )
            }
        }
        if (!following && selectedIds == null && !(searching && query.isNotBlank())) {
            JumpToNewestButton(
                onClick = {
                    following = true
                    scope.launch { listState.scrollToEnd(visible.lastIndex, animated = true) }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(CTLayout.edgePad),
            )
        }
        }

        quoting?.let { target ->
            QuoteSelectionSheet(
                text = target.body,
                onConfirm = { viewModel.startReply(target, it) },
                onDismiss = { quoting = null },
            )
        }

        if (pickingSticker) {
            com.construct.messenger.ui.components.StickerPickerSheet(
                stickers = stickersVm,
                onSend = { followNext(); viewModel.sendSticker(it) },
                onDismiss = { pickingSticker = false },
            )
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
        selectedIds?.let { chosen ->
            SelectionBar(
                count = chosen.size,
                onDelete = {
                    viewModel.deleteAll(chosen)
                    selectedIds = null
                },
            )
            return@Column
        }
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
            onVideoNote = if (uiState.editingOriginal == null) ({ startVideoNote() }) else null,
            voiceBar = when (val r = recording) {
                is VoiceRecorder.State.Recording -> {
                    { VoiceComposerBar(true, r.durationMs, r.recent, viewModel::cancelRecording, viewModel::stopRecording) }
                }
                is VoiceRecorder.State.Recorded -> {
                    { VoiceComposerBar(false, r.durationMs, r.waveform, viewModel::cancelRecording, { followNext(); viewModel.sendRecording() }) }
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
                        text = { Text(stringResource(R.string.attach_photo_video), style = CTFont.ui(14), color = CTColor.text) },
                        leadingIcon = { Icon(Icons.Filled.Image, null, tint = CTColor.text) },
                        onClick = {
                            attachMenuOpen = false
                            pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                        },
                    )
                    // iOS: stickers are the picker sheet's tab next to Gallery; here the sheet is a menu.
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.media_picker_tab_stickers), style = CTFont.ui(14), color = CTColor.text) },
                        leadingIcon = { Icon(Icons.Outlined.EmojiEmotions, null, tint = CTColor.text) },
                        onClick = {
                            attachMenuOpen = false
                            pickingSticker = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.attach_file), style = CTFont.ui(14), color = CTColor.text) },
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
            onSend = { followNext(); viewModel.send() },
            replyPreview = uiState.replyingTo?.let { reply ->
                reply.preview.ifBlank { quoteFallback(reply.mediaType, reply.videoNote) }
            },
            onCancelReply = viewModel::cancelReply,
            editingPreview = uiState.editingOriginal?.ifBlank { stringResource(R.string.photo) },
            onCancelEdit = viewModel::cancelEdit,
        )
    }
    if (recordingNote) {
        VideoNoteRecordingOverlay(
            onSend = { followNext(); viewModel.sendVideoNote(it) },
            onClose = { recordingNote = false },
        )
    }
    ChatActionPaletteOverlay(actionPalette)
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
    val quoted = transcript.firstOrNull { it.id.equals(quotedId, ignoreCase = true) }
    val local = quoted?.body
    if (!local.isNullOrBlank()) return local
    // The wire names a note a video; the quoted row here knows better (iOS, locally only).
    val note = (quoted?.media as? com.construct.messenger.data.model.MessageMedia.Album)?.videoNote != null
    return quoteFallback(message.replyMediaType, note)
}

@Composable
private fun quoteFallback(mediaType: String?, videoNote: Boolean = false): String = when {
    videoNote -> stringResource(R.string.video_note)
    else -> quoteKind(mediaType)
}

@Composable
private fun quoteKind(mediaType: String?): String = when (mediaType) {
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
 * (tapping it opens the profile), search on the trailing side; while messages are being selected,
 * Done in its place. The call button sits before search, and only while no call is on (iOS
 * `canStartCall`).
 */
@Composable
private fun ChatNavBar(
    title: String,
    onBack: () -> Unit,
    onOpenProfile: () -> Unit,
    selecting: Boolean,
    onDoneSelecting: () -> Unit,
    onSearch: () -> Unit,
    onCall: (() -> Unit)?,
    onVideoCall: (() -> Unit)?,
    alerted: Boolean,
    ktVerified: Boolean,
    onVerify: () -> Unit,
    actionPalette: ChatActionPaletteHost,
) {
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
                modifier = Modifier.size(CTIcon.nav),
            )
        }
        Spacer(Modifier.width(CTLayout.inlinePad))
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title.uppercase(),
                style = CTFont.headline,
                color = CTColor.text,
                letterSpacing = 4.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .clickable(onClick = onOpenProfile)
                    .padding(vertical = 10.dp),
            )
            KtBadge(alerted = alerted, verified = ktVerified, onAlertTap = onVerify)
        }
        if (selecting) {
            Text(
                text = stringResource(R.string.done),
                style = CTFont.headline,
                color = CTColor.accent,
                modifier = Modifier
                    .clickable(onClick = onDoneSelecting)
                    .padding(horizontal = CTLayout.inlinePad, vertical = 10.dp),
            )
        } else {
            // Search, call, video call — one button, one palette (decisions/chat-header-actions-are-one-palette).
            ChatActionButton(
                actions = ChatAction.available(canCall = onCall != null && onVideoCall != null),
                host = actionPalette,
                onAction = { action ->
                    when (action) {
                        ChatAction.SEARCH -> onSearch()
                        ChatAction.CALL -> onCall?.invoke()
                        ChatAction.VIDEO_CALL -> onVideoCall?.invoke()
                    }
                },
            )
        }
    }
}

/** iOS `ChatSearchChromeView`: the field, a close button, and how many messages match. */
@Composable
private fun ChatSearchBar(query: String, onQueryChange: (String) -> Unit, resultCount: Int, onClose: () -> Unit) {
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(CTLayout.navBarHeight)
                .glassCapsule()
                .padding(start = CTLayout.edgePad),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Search, contentDescription = null, tint = CTColor.textDim, modifier = Modifier.size(CTIcon.nav))
            Spacer(Modifier.width(CTLayout.inlinePad))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    Text(stringResource(R.string.search_messages), style = CTFont.ui(14), color = CTColor.textDim)
                }
                androidx.compose.foundation.text.BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = CTFont.ui(14).copy(color = CTColor.text),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(CTColor.accent),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            Box(
                modifier = Modifier.size(CTLayout.hitTarget).clip(CircleShape).clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Cancel, contentDescription = stringResource(R.string.close), tint = CTColor.textDim, modifier = Modifier.size(CTLayout.navIconSize))
            }
        }
        if (query.isNotEmpty()) {
            Text(
                text = androidx.compose.ui.res.pluralStringResource(R.plurals.chat_search_results, resultCount, resultCount),
                style = CTFont.secondary,
                color = CTColor.textDim,
                modifier = Modifier.padding(horizontal = CTLayout.inlinePad, vertical = 4.dp),
            )
        }
    }
}

/** iOS `ChatSelectionBarView`: Delete Selected and how many are chosen, in the composer's place. */
@Composable
private fun SelectionBar(count: Int, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .background(CTColor.bgMsg, androidx.compose.foundation.shape.RoundedCornerShape(com.construct.messenger.ui.theme.CornerRadius.small))
            .height(CTLayout.controlHeight)
            .padding(horizontal = CTLayout.edgePad),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.delete_selected).uppercase(),
            style = CTFont.ui(14),
            color = if (count > 0) CTColor.danger else CTColor.textDim,
            modifier = Modifier
                .clickable(enabled = count > 0, onClick = onDelete)
                .padding(vertical = 10.dp),
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = androidx.compose.ui.res.pluralStringResource(R.plurals.messages_selected, count, count),
            style = CTFont.secondary,
            color = CTColor.textDim,
        )
    }
}

/**
 * iOS `QuoteSelectionSheet`: the message's text, selectable; the selection becomes the quote of
 * the reply. A read-only field and not a `SelectionContainer` — only a field says what is selected.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun QuoteSelectionSheet(text: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(text)) }
    val selected = value.text.substring(value.selection.min, value.selection.max)
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, containerColor = CTColor.bg) {
        Column(Modifier.padding(horizontal = CTLayout.edgePad).padding(bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.select_quote), style = CTFont.headline, color = CTColor.text, modifier = Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.reply_with_selection),
                    style = CTFont.headline,
                    color = if (selected.isNotBlank()) CTColor.accent else CTColor.textDim,
                    modifier = Modifier
                        .clickable(enabled = selected.isNotBlank()) {
                            onConfirm(selected)
                            onDismiss()
                        }
                        .padding(vertical = 10.dp),
                )
            }
            Text(stringResource(R.string.quote_selection_hint), style = CTFont.secondary, color = CTColor.textDim)
            Spacer(Modifier.height(CTLayout.inlinePad))
            androidx.compose.foundation.text.BasicTextField(
                value = value,
                onValueChange = { value = it.copy(text = text) },
                readOnly = true,
                textStyle = CTFont.ui(15).copy(color = CTColor.text),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CTColor.bgMsg)
                    .padding(12.dp),
            )
            if (selected.isNotBlank()) {
                Spacer(Modifier.height(CTLayout.inlinePad))
                Row(Modifier.background(CTColor.bgMsg).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(2.dp).height(28.dp).background(CTColor.accent))
                    Text(
                        text = selected,
                        style = CTFont.secondary,
                        color = CTColor.textDim,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

private val NEAR_BOTTOM = 60.dp

/** iOS `ChatUIConstants.VideoNote.expandDuration`: the expansion settles before the scroll. */
private const val VIDEO_NOTE_EXPAND_MS = 350L

/**
 * To the end of the last row, not its top: `scrollToItem` puts a row's top at the viewport's, and
 * a message taller than the screen then shows its beginning — the jump control stayed up.
 */
private suspend fun androidx.compose.foundation.lazy.LazyListState.scrollToEnd(lastIndex: Int, animated: Boolean = false) {
    if (lastIndex < 0) return
    if (animated) animateScrollToItem(lastIndex) else scrollToItem(lastIndex)
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull { it.index == lastIndex } ?: return
    val below = (last.offset + last.size - info.viewportEndOffset).toFloat()
    if (below <= 0f) return
    if (animated) animateScrollBy(below) else scrollBy(below)
}

/** iOS `ChatView` jump control: `chevron.down` in accent on a round surface, `scroll_to_newest`. */
@Composable
private fun JumpToNewestButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.scroll_to_newest)
    Box(
        modifier = modifier
            .size(CTLayout.controlHeight)
            .background(CTColor.bgMsg, CircleShape)
            .clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.KeyboardArrowDown,
            contentDescription = label,
            tint = CTColor.accent,
            modifier = Modifier.size(CTLayout.callIconSize),
        )
    }
}

/**
 * Beside the name: a red shield while there is something to warn about (tap → safety numbers),
 * else an accent check when the key server's proof for their key verified.
 * **Canon:** iOS `ChatNavBarView.ktBadge`.
 */
@Composable
private fun KtBadge(alerted: Boolean, verified: Boolean, onAlertTap: () -> Unit) {
    when {
        alerted -> Box(
            modifier = Modifier
                .padding(start = CTLayout.inlinePad)
                .size(CTLayout.hitTarget * 0.7f)
                .clip(CircleShape)
                .clickable(onClickLabel = stringResource(R.string.key_change_verify), onClick = onAlertTap),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.GppMaybe,
                contentDescription = stringResource(R.string.kt_warning),
                tint = CTColor.danger,
                modifier = Modifier.size(CTLayout.navIconSize),
            )
        }
        verified -> Icon(
            Icons.Filled.CheckCircle,
            contentDescription = stringResource(R.string.kt_verified),
            tint = CTColor.accent,
            modifier = Modifier.padding(start = CTLayout.inlinePad).size(CTIcon.row),
        )
    }
}
