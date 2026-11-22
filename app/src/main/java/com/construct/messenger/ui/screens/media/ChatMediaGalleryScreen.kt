package com.construct.messenger.ui.screens.media

import android.content.ActivityNotFoundException
import android.content.Intent
import android.text.format.DateUtils
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.construct.messenger.R
import com.construct.messenger.data.model.MediaItem
import com.construct.messenger.data.model.MessageMedia
import com.construct.messenger.ui.components.CTNavBar
import com.construct.messenger.ui.components.MediaGridTile
import com.construct.messenger.ui.components.MediaTileGap
import com.construct.messenger.ui.components.MediaViewer
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTFont
import com.construct.messenger.ui.theme.CTIcon
import com.construct.messenger.ui.theme.CTLayout
import com.construct.messenger.ui.theme.CTSpace
import com.construct.messenger.viewmodel.ChatMediaGalleryViewModel
import com.construct.messenger.viewmodel.ChatMediaTab

/**
 * A chat's media (vault todo 327): pictures and videos as a grid, files as a list. Entered from the
 * person's profile. **Canon:** iOS `ChatMediaGalleryView` — the album bubble's own tile, the chat's
 * full-screen viewer over what is loaded, oldest first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatMediaGalleryScreen(
    onNavigateBack: () -> Unit,
    viewModel: ChatMediaGalleryViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val loading by viewModel.fileLoading.collectAsStateWithLifecycle()
    val unavailable by viewModel.fileUnavailable.collectAsStateWithLifecycle()
    var viewing by remember { mutableStateOf<MediaItem?>(null) }
    GalleryEvents(viewModel)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CTColor.bg)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        CTNavBar(title = stringResource(R.string.chat_media_title), showBack = true, onBack = onNavigateBack)
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = CTLayout.edgePad, vertical = CTSpace.s),
        ) {
            ChatMediaTab.entries.forEachIndexed { index, tab ->
                SegmentedButton(
                    selected = tab == ui.tab,
                    onClick = { viewModel.select(tab) },
                    shape = SegmentedButtonDefaults.itemShape(index, ChatMediaTab.entries.size),
                ) {
                    Text(
                        stringResource(
                            when (tab) {
                                ChatMediaTab.VISUAL -> R.string.chat_media_tab_visual
                                ChatMediaTab.FILES -> R.string.chat_media_tab_files
                            },
                        ),
                    )
                }
            }
        }
        val empty = ui.isComplete && when (ui.tab) {
            ChatMediaTab.VISUAL -> ui.tiles.isEmpty()
            ChatMediaTab.FILES -> ui.files.isEmpty()
        }
        when {
            empty -> Empty(ui.tab)
            ui.tab == ChatMediaTab.VISUAL -> BoxWithConstraints(Modifier.fillMaxSize()) {
                val side = (maxWidth - MediaTileGap * 2) / 3
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    horizontalArrangement = Arrangement.spacedBy(MediaTileGap),
                    verticalArrangement = Arrangement.spacedBy(MediaTileGap),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(ui.tiles, key = { _, tile -> "${tile.entry.messageId}_${tile.entry.item}" }) { index, tile ->
                        LaunchedEffect(tile.entry) { viewModel.shown(index) }
                        MediaGridTile(tile.media, side, onTap = { viewing = tile.media })
                    }
                }
            }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(ui.files, key = { _, row -> row.messageId }) { index, row ->
                    LaunchedEffect(row.messageId) { viewModel.shown(index) }
                    row.files.forEach { file ->
                        FileRow(
                            file = file,
                            timestampMs = row.timestampMs,
                            loading = file.mediaId in loading,
                            unavailable = file.mediaId in unavailable,
                            onOpen = { viewModel.openFile(file) },
                        )
                    }
                }
            }
        }
    }

    viewing?.let { start ->
        // The viewer pages the loaded pictures oldest first, as it does in the chat.
        val pictures = ui.tiles.map { it.media }.reversed()
        MediaViewer(
            album = MessageMedia.Album(pictures),
            startIndex = pictures.indexOf(start).coerceAtLeast(0),
            onDismiss = { viewing = null },
            loadVideo = viewModel::mediaBytes,
            onSave = viewModel::saveToGallery,
            onShare = viewModel::share,
        )
    }
}

/** Open, share and save results — the chat screen's handling of the same events. */
@Composable
private fun GalleryEvents(viewModel: ChatMediaGalleryViewModel) {
    val context = LocalContext.current
    val noApp = stringResource(R.string.no_app_to_open)
    val saved = stringResource(R.string.media_saved)
    val saveFailed = stringResource(R.string.media_save_failed)
    LaunchedEffect(Unit) {
        viewModel.openFile.collect { open ->
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(open.uri, open.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try {
                context.startActivity(Intent.createChooser(view, null))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(context, noApp, Toast.LENGTH_LONG).show()
            }
        }
    }
    LaunchedEffect(Unit) {
        viewModel.shareFile.collect { file ->
            val send = Intent(Intent.ACTION_SEND).setType(file.mime).putExtra(Intent.EXTRA_STREAM, file.uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(send, null))
        }
    }
    LaunchedEffect(Unit) {
        viewModel.galleryResult.collect { ok ->
            Toast.makeText(context, if (ok) saved else saveFailed, Toast.LENGTH_SHORT).show()
        }
    }
}

/** A file: its name, size and day; a tap fetches and opens it, as the chat's file row does. */
@Composable
private fun FileRow(file: MediaItem, timestampMs: Long, loading: Boolean, unavailable: Boolean, onOpen: () -> Unit) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !loading && !unavailable, onClick = onOpen)
            .padding(horizontal = CTLayout.edgePad, vertical = CTSpace.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CTSpace.m),
    ) {
        Box(Modifier.size(CTIcon.navLg), contentAlignment = Alignment.Center) {
            if (loading) {
                CircularProgressIndicator(color = CTColor.textDim, strokeWidth = 2.dp, modifier = Modifier.size(CTIcon.row))
            } else {
                Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = null, tint = CTColor.accent)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = file.filename ?: stringResource(R.string.file_attachment),
                style = CTFont.ui(14),
                color = CTColor.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val size = if (file.sizeBytes > 0) Formatter.formatShortFileSize(context, file.sizeBytes) else null
            val day = DateUtils.formatDateTime(context, timestampMs, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)
            Text(
                text = if (unavailable) stringResource(R.string.media_unavailable) else listOfNotNull(size, day).joinToString(" · "),
                style = CTFont.caption,
                color = if (unavailable) CTColor.danger else CTColor.textDim,
            )
        }
    }
}

@Composable
private fun Empty(tab: ChatMediaTab) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CTSpace.s)) {
            Icon(
                if (tab == ChatMediaTab.VISUAL) Icons.Filled.PhotoLibrary else Icons.AutoMirrored.Filled.InsertDriveFile,
                contentDescription = null,
                tint = CTColor.textDim,
                modifier = Modifier.size(CTIcon.navLg),
            )
            Text(
                stringResource(if (tab == ChatMediaTab.VISUAL) R.string.chat_media_empty_visual else R.string.chat_media_empty_files),
                style = CTFont.ui(14),
                color = CTColor.textDim,
            )
        }
    }
}
