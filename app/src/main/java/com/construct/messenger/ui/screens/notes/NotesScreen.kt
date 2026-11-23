package com.construct.messenger.ui.screens.notes

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.construct.messenger.R
import com.construct.messenger.data.local.Note
import com.construct.messenger.data.model.MessageMedia
import com.construct.messenger.ui.components.CTConfirmDialog
import com.construct.messenger.ui.components.CTNavBar
import com.construct.messenger.ui.components.FilterSearchBar
import com.construct.messenger.ui.components.MediaGridTile
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTFont
import com.construct.messenger.ui.theme.CTIcon
import com.construct.messenger.ui.theme.CTLayout
import com.construct.messenger.ui.theme.CTSpace
import com.construct.messenger.viewmodel.NotesViewModel

@Composable
fun NotesRoute(
    onNavigateBack: () -> Unit,
    onOpen: (noteId: String) -> Unit,
    viewModel: NotesViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var writing by rememberSaveable { mutableStateOf(false) }
    NotesScreen(
        ui = ui,
        onNavigateBack = onNavigateBack,
        onQuery = viewModel::setQuery,
        onShown = viewModel::shown,
        onOpen = { onOpen(it.id) },
        onNew = { writing = true },
        onTogglePin = viewModel::togglePin,
        onDelete = viewModel::delete,
    )
    if (writing) NoteEditorSheet("", onSave = viewModel::add, onDismiss = { writing = false })
}

/**
 * Settings → Notes (vault todo 279): pinned first, then newest; search. A tap opens, a long press
 * offers pin, copy and delete. **Canon:** iOS `NotesView`.
 */
@Composable
private fun NotesScreen(
    ui: NotesViewModel.UiState,
    onNavigateBack: () -> Unit,
    onQuery: (String) -> Unit,
    onShown: (Int) -> Unit,
    onOpen: (Note) -> Unit,
    onNew: () -> Unit,
    onTogglePin: (Note) -> Unit,
    onDelete: (Note) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var deleting by remember { mutableStateOf<Note?>(null) }
    val notes = ui.notes
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CTColor.bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        CTNavBar(
            title = stringResource(R.string.notes_title),
            showBack = true,
            onBack = onNavigateBack,
            trailingIcon = Icons.AutoMirrored.Outlined.NoteAdd,
            trailingDescription = stringResource(R.string.notes_new),
            onTrailingAction = onNew,
        )
        FilterSearchBar(
            query = query,
            onQueryChange = {
                query = it
                onQuery(it)
            },
            modifier = Modifier.padding(horizontal = CTLayout.edgePad).padding(vertical = CTSpace.s),
        )
        when {
            notes == null -> Unit
            notes.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth().padding(CTSpace.l), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(if (ui.isSearching) R.string.notes_nothing_found else R.string.notes_stay_here),
                    style = CTFont.body,
                    color = CTColor.textDim,
                    textAlign = TextAlign.Center,
                )
            }
            else -> LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                itemsIndexed(notes, key = { _, note -> note.id }) { index, note ->
                    LaunchedEffect(note.id) { onShown(index) }
                    NoteRow(note, onOpen = { onOpen(note) }, onTogglePin = { onTogglePin(note) }, onDelete = { deleting = note })
                    HorizontalDivider()
                }
            }
        }
    }
    deleting?.let { note ->
        DeleteNoteDialog(onDelete = { onDelete(note) }, onDismiss = { deleting = null })
    }
}

/** A note holds what may exist nowhere else: deleting one is asked about. */
@Composable
internal fun DeleteNoteDialog(onDelete: () -> Unit, onDismiss: () -> Unit) {
    CTConfirmDialog(
        title = stringResource(R.string.notes_delete_title),
        message = stringResource(R.string.notes_delete_message),
        confirmLabel = stringResource(R.string.delete),
        dismissLabel = stringResource(R.string.action_cancel),
        onConfirm = {
            onDelete()
            onDismiss()
        },
        onDismiss = onDismiss,
        isDestructive = true,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteRow(note: Note, onOpen: () -> Unit, onTogglePin: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onOpen, onLongClick = { menu = true })
                .padding(horizontal = CTLayout.edgePad, vertical = CTSpace.m),
            horizontalArrangement = Arrangement.spacedBy(CTSpace.m),
        ) {
            NoteLead(note, onOpen)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CTSpace.xs)) {
                Text(
                    text = noteSummary(note),
                    // What a person wrote: in the face they chose for messages.
                    style = CTFont.message(15),
                    color = CTColor.text,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(noteOrigin(note), style = CTFont.secondary, color = CTColor.textDim)
            }
            if (note.isPinned) {
                Icon(
                    Icons.Filled.PushPin,
                    contentDescription = stringResource(R.string.notes_pinned),
                    tint = CTColor.accent,
                    modifier = Modifier.size(CTIcon.row),
                )
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            MenuRow(stringResource(if (note.isPinned) R.string.notes_unpin else R.string.notes_pin), Icons.Outlined.PushPin) {
                menu = false
                onTogglePin()
            }
            if (note.text.isNotEmpty()) {
                MenuRow(stringResource(R.string.copy), Icons.Filled.ContentCopy) {
                    menu = false
                    clipboard.setText(AnnotatedString(note.text))
                }
            }
            MenuRow(stringResource(R.string.delete), Icons.Filled.Delete, danger = true) {
                menu = false
                onDelete()
            }
        }
    }
}

@Composable
internal fun MenuRow(label: String, icon: ImageVector, danger: Boolean = false, onClick: () -> Unit) {
    val tint = if (danger) CTColor.danger else CTColor.text
    DropdownMenuItem(
        text = { Text(label, style = CTFont.ui(14), color = tint) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = tint) },
        onClick = onClick,
    )
}

/** A picture's own tile, or a symbol of what else it holds. */
@Composable
private fun NoteLead(note: Note, onOpen: () -> Unit) {
    when (val media = note.media) {
        is MessageMedia.Album -> if (media.isFiles) {
            LeadIcon(Icons.AutoMirrored.Filled.InsertDriveFile)
        } else {
            media.items.firstOrNull { it.isImage || it.isVideo }?.let { MediaGridTile(it, CTLayout.hitTarget, onTap = onOpen) }
                ?: LeadIcon(Icons.Filled.PhotoLibrary)
        }
        is MessageMedia.Voice -> LeadIcon(Icons.Filled.Mic)
        is MessageMedia.Sticker -> LeadIcon(Icons.Outlined.EmojiEmotions)
        null -> Unit
    }
}

@Composable
private fun LeadIcon(icon: ImageVector) {
    Box(Modifier.size(CTLayout.hitTarget), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = CTColor.accent)
    }
}

/** The words; for files without any, their names; else what it holds. */
@Composable
private fun noteSummary(note: Note): String {
    if (note.text.isNotEmpty()) return note.text
    return when (val media = note.media) {
        is MessageMedia.Album -> if (media.isFiles) {
            media.items.mapNotNull { it.filename?.ifBlank { null } }.joinToString().ifEmpty { stringResource(R.string.file_attachment) }
        } else {
            stringResource(if (media.items.any { it.isVideo }) R.string.video else R.string.photo)
        }
        is MessageMedia.Voice -> stringResource(R.string.voice_message)
        is MessageMedia.Sticker -> "${media.ref.emoji} ${stringResource(R.string.sticker)}"
        null -> ""
    }
}

/** Whose message it was and when; for one's own words, when they were written. */
@Composable
internal fun noteOrigin(note: Note): String {
    val context = LocalContext.current
    fun stamp(ms: Long) = DateUtils.formatDateTime(
        context, ms,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_TIME,
    )
    val name = note.sourceName
    val at = note.sourceAtMs
    return if (name != null && at != null) stringResource(R.string.notes_from, name, stamp(at)) else stamp(note.editedAtMs ?: note.createdAtMs)
}

@Preview(backgroundColor = 0xFF090909, showBackground = true, heightDp = 700, widthDp = 360)
@Composable
private fun NotesScreenPreview() {
    NotesScreen(
        ui = NotesViewModel.UiState(
            listOf(
                Note.ofText("Remember to verify safety numbers with Alex", "1", 1_759_100_000_000).copy(isPinned = true),
                Note.ofText("Invite link for the team — send tomorrow", "2", 1_759_000_000_000).copy(sourceName = "Alex", sourceAtMs = 1_758_000_000_000),
            ),
        ),
        onNavigateBack = {}, onQuery = {}, onShown = {}, onOpen = {}, onNew = {}, onTogglePin = {}, onDelete = {},
    )
}
