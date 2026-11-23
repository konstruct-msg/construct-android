package com.construct.messenger.ui.screens.notes

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.construct.messenger.R
import com.construct.messenger.data.local.Note
import com.construct.messenger.data.model.MessageMedia
import com.construct.messenger.ui.components.CTNavBar
import com.construct.messenger.ui.components.LinkedText
import com.construct.messenger.ui.components.MediaAlbumView
import com.construct.messenger.ui.components.MediaViewer
import com.construct.messenger.ui.components.StickerBubble
import com.construct.messenger.ui.components.VoiceBubble
import com.construct.messenger.ui.components.VoicePlayback
import com.construct.messenger.ui.screens.chat.ForwardPickerSheet
import com.construct.messenger.ui.screens.media.MediaActionEvents
import com.construct.messenger.ui.screens.media.MediaFileRow
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTFont
import com.construct.messenger.ui.theme.CTLayout
import com.construct.messenger.ui.theme.CTSpace
import com.construct.messenger.viewmodel.NoteViewModel
import com.construct.messenger.viewmodel.StickersViewModel

/** One note (vault todo 279): what it says and holds, drawn by the message bubbles' own views. */
@Composable
fun NoteScreen(
    onNavigateBack: () -> Unit,
    onOpenChat: (contactId: String) -> Unit,
    viewModel: NoteViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val problem by viewModel.problem.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val notSaved = stringResource(R.string.notes_not_saved)
    MediaActionEvents(viewModel.actions)
    LaunchedEffect(problem) {
        if (problem) {
            Toast.makeText(context, notSaved, Toast.LENGTH_LONG).show()
            viewModel.problemShown()
        }
    }
    // Deleted here or elsewhere: nothing left to show.
    LaunchedEffect(ui.gone) { if (ui.gone) onNavigateBack() }

    val note = ui.note
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CTColor.bg)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Box {
            CTNavBar(
                title = stringResource(R.string.notes_note),
                showBack = true,
                onBack = onNavigateBack,
                trailingIcon = Icons.Filled.MoreVert,
                trailingDescription = stringResource(R.string.chat_actions),
                onTrailingAction = { menu = true },
            )
            Box(Modifier.align(Alignment.BottomEnd)) {
                DropdownMenu(expanded = menu && note != null, onDismissRequest = { menu = false }) {
                    if (note == null) return@DropdownMenu
                    if (note.isEditable) {
                        MenuRow(stringResource(R.string.edit_message), Icons.Filled.Edit) { menu = false; editing = true }
                    }
                    MenuRow(stringResource(R.string.notes_send), Icons.AutoMirrored.Filled.Forward) { menu = false; sending = true }
                    if (note.text.isNotEmpty()) {
                        MenuRow(stringResource(R.string.copy), Icons.Filled.ContentCopy) {
                            menu = false
                            clipboard.setText(AnnotatedString(note.text))
                        }
                        MenuRow(stringResource(R.string.notes_share), Icons.Filled.Share) {
                            menu = false
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, note.text)
                            context.startActivity(Intent.createChooser(send, null))
                        }
                    }
                    MenuRow(stringResource(if (note.isPinned) R.string.notes_unpin else R.string.notes_pin), Icons.Outlined.PushPin) {
                        menu = false
                        viewModel.togglePin(note)
                    }
                    MenuRow(stringResource(R.string.delete), Icons.Filled.Delete, danger = true) { menu = false; deleting = true }
                }
            }
        }
        if (note != null) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = CTSpace.m),
                verticalArrangement = Arrangement.spacedBy(CTSpace.m),
            ) {
                Text(
                    noteOrigin(note),
                    style = CTFont.secondary,
                    color = CTColor.textDim,
                    modifier = Modifier.padding(horizontal = CTLayout.edgePad),
                )
                NoteContent(note, viewModel)
            }
        }
    }

    if (note == null) return
    if (editing) {
        NoteEditorSheet(note.text, onSave = { text, saved -> viewModel.edit(note, text, saved) }, onDismiss = { editing = false })
    }
    if (deleting) DeleteNoteDialog(onDelete = { viewModel.delete(note) }, onDismiss = { deleting = false })
    if (sending) {
        ForwardPickerSheet(
            onPick = { chat -> if (viewModel.send(note, chat.contactId)) onOpenChat(chat.contactId) },
            onDismiss = { sending = false },
        )
    }
}

@Composable
private fun NoteContent(note: Note, viewModel: NoteViewModel) {
    val loading by viewModel.actions.fileLoading.collectAsStateWithLifecycle()
    val unavailable by viewModel.actions.fileUnavailable.collectAsStateWithLifecycle()
    val edge = Modifier.padding(horizontal = CTLayout.edgePad)
    when (val media = note.media) {
        is MessageMedia.Album -> if (media.isFiles) {
            Column {
                media.items.forEach { file ->
                    MediaFileRow(
                        file = file,
                        timestampMs = note.sourceAtMs ?: note.createdAtMs,
                        loading = file.mediaId in loading,
                        unavailable = file.mediaId in unavailable,
                        onOpen = { viewModel.actions.openFile(file) },
                    )
                }
            }
        } else {
            var viewing by remember { mutableStateOf<Int?>(null) }
            Box(edge) { MediaAlbumView(album = media, onOpen = { viewing = it }, onLongPress = {}) }
            viewing?.let { start ->
                MediaViewer(
                    album = media,
                    startIndex = start,
                    onDismiss = { viewing = null },
                    loadVideo = viewModel.actions::mediaBytes,
                    onSave = viewModel.actions::saveToGallery,
                    onShare = viewModel.actions::share,
                )
            }
        }
        is MessageMedia.Voice -> {
            val playing by viewModel.playing.collectAsStateWithLifecycle()
            val rate by viewModel.voiceRate.collectAsStateWithLifecycle()
            val id = media.audio.mediaId
            val mine = playing?.takeIf { it.mediaId == id }
            Box(edge) {
                VoiceBubble(
                    voice = media,
                    outgoing = false,
                    playback = VoicePlayback(
                        progress = mine?.progress,
                        paused = mine?.paused ?: false,
                        playingDurationMs = mine?.durationMs ?: 0,
                        loading = id in loading,
                        unavailable = id in unavailable,
                        rate = rate,
                    ),
                    maxWidth = 300.dp,
                    onToggle = { viewModel.toggleVoice(media) },
                    onLongPress = {},
                    onSeek = { viewModel.seekVoice(media, it) },
                    onCycleRate = viewModel::cycleVoiceRate,
                )
            }
        }
        is MessageMedia.Sticker -> {
            val stickers: StickersViewModel = hiltViewModel()
            val generation by stickers.generation.collectAsStateWithLifecycle()
            Box(edge) {
                StickerBubble(
                    ref = media.ref,
                    file = generation.let { stickers.file(media.ref) },
                    onMissing = { stickers.ensure(media.ref) },
                    onLongPress = {},
                    onDoubleTap = {},
                )
            }
        }
        null -> Unit
    }
    if (note.text.isNotEmpty()) {
        SelectionContainer(edge) {
            LinkedText(
                text = note.text,
                style = CTFont.message(CTFont.MESSAGE_SIZE),
                color = CTColor.text,
                linkColor = CTColor.accent,
                onLongPress = {},
                onDoubleTap = {},
            )
        }
    }
}

/** Writing a note, or the words of one. It stays, with what was typed, until the note is stored. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NoteEditorSheet(initial: String, onSave: (String, onSaved: () -> Unit) -> Unit, onDismiss: () -> Unit) {
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    val focus = remember { FocusRequester() }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = CTColor.bg,
    ) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = CTLayout.edgePad).padding(bottom = CTSpace.l)) {
            TextButton(
                onClick = { onSave(field.text, onDismiss) },
                enabled = field.text.isNotBlank(),
                modifier = Modifier.align(Alignment.End),
            ) { Text(stringResource(R.string.notes_save).uppercase()) }
            BasicTextField(
                value = field,
                onValueChange = { field = it },
                textStyle = CTFont.message(CTFont.MESSAGE_SIZE).copy(color = CTColor.text),
                cursorBrush = SolidColor(CTColor.accent),
                modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp).focusRequester(focus),
            )
        }
        LaunchedEffect(Unit) { focus.requestFocus() }
    }
}
