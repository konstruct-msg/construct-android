@file:OptIn(ExperimentalMaterial3Api::class)

package com.construct.messenger.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.construct.messenger.R
import com.construct.messenger.stickers.StickerPack
import com.construct.messenger.stickers.StickerPackRowAction
import com.construct.messenger.stickers.StickerStore
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTFont
import com.construct.messenger.ui.theme.CTIcon
import com.construct.messenger.ui.theme.CTLayout
import com.construct.messenger.ui.theme.CTSpace
import com.construct.messenger.viewmodel.StickersViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Every pack in one list, opened by Add stickers in the picker: the installed packs, each with
 * Remove, then the server's catalog minus those, each with Get. Same meaning as iOS
 * `StickerPacksView`. Get fetches the whole pack and checks it (`StickersViewModel.install`);
 * Remove keeps the bytes for the transcript (`uninstall`).
 */
@Composable
fun StickerPacksSheet(stickers: StickersViewModel, onDismiss: () -> Unit) {
    val picker by stickers.picker.collectAsStateWithLifecycle()
    val catalog by stickers.catalog.collectAsStateWithLifecycle()
    val installing by stickers.installing.collectAsStateWithLifecycle()
    val failed by stickers.installFailed.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { stickers.loadCatalog() }
    val installed = picker.packs.map { it.hex }.toSet()
    val more = (catalog as? StickersViewModel.Catalog.Loaded)?.packs.orEmpty().filter { it.hex !in installed }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = CTColor.bg) {
        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = CTLayout.edgePad)) {
            item(key = "title") {
                Text(
                    stringResource(R.string.sticker_packs_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = CTColor.text,
                )
            }
            if (picker.packs.isNotEmpty()) {
                item(key = "mine") { SectionTitle(stringResource(R.string.sticker_packs_mine)) }
                items(picker.packs, key = { "i-${it.hex}" }) { pack ->
                    InstalledPackRow(pack, stickers)
                }
            }
            item(key = "more") { SectionTitle(stringResource(R.string.sticker_picker_more_packs)) }
            if (more.isNotEmpty()) {
                items(more, key = { "c-${it.hex}" }) { entry ->
                    CatalogPackRow(
                        entry,
                        stickers,
                        StickerPackRowAction.of(installed = false, installing = entry.hex in installing, failed = entry.hex in failed),
                    )
                }
            } else {
                item(key = "more-state") {
                    when (catalog) {
                        StickersViewModel.Catalog.Loading -> Box(Modifier.fillMaxWidth().padding(vertical = CTSpace.m), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = CTColor.accent, modifier = Modifier.size(CTIcon.control))
                        }
                        StickersViewModel.Catalog.Unavailable -> Row(Modifier.padding(vertical = CTSpace.s), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.sticker_catalog_failed), style = CTFont.secondary, color = CTColor.textDim, modifier = Modifier.weight(1f))
                            TextButton(onClick = { stickers.loadCatalog() }) { Text(stringResource(R.string.retry)) }
                        }
                        is StickersViewModel.Catalog.Loaded -> Text(
                            stringResource(R.string.sticker_packs_all_added),
                            style = CTFont.secondary,
                            color = CTColor.textDim,
                            modifier = Modifier.padding(vertical = CTSpace.s),
                        )
                    }
                }
            }
            item(key = "bottom") { Spacer(Modifier.height(CTSpace.xl)) }
        }
    }
}

@Composable
private fun InstalledPackRow(pack: StickerPack, stickers: StickersViewModel) {
    val cover = rememberSticker(pack.stickers.firstOrNull()?.let(stickers::file), targetPx = COVER_PX)
    PackRow(
        cover = cover,
        title = pack.title,
        meta = "${pack.publisher} · ${pluralStringResource(R.plurals.sticker_pack_count, pack.stickers.size, pack.stickers.size)}",
        action = StickerPackRowAction.REMOVE,
        onAction = { stickers.uninstall(pack) },
    )
}

@Composable
private fun CatalogPackRow(entry: StickerStore.CatalogEntry, stickers: StickersViewModel, action: StickerPackRowAction) {
    val cover by produceState<ImageBitmap?>(null, entry.hex) {
        value = stickers.cover(entry.coverSha256)?.let { bytes ->
            withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
        }
    }
    val meta = if (action == StickerPackRowAction.RETRY) {
        stringResource(R.string.sticker_install_failed)
    } else {
        "${entry.publisher} · ${pluralStringResource(R.plurals.sticker_pack_count, entry.count, entry.count)} · ${entry.totalBytes / 1024} KB"
    }
    PackRow(cover = cover, title = entry.title, meta = meta, action = action, onAction = { stickers.install(entry) })
}

@Composable
private fun PackRow(
    cover: ImageBitmap?,
    title: String,
    meta: String,
    action: StickerPackRowAction,
    onAction: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = CTSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CTLayout.inlinePad),
    ) {
        Box(Modifier.size(CTLayout.stickerCover), contentAlignment = Alignment.Center) {
            cover?.let { Image(it, null, contentScale = ContentScale.Fit, modifier = Modifier.size(CTLayout.stickerCover)) }
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = CTFont.body, color = CTColor.text, maxLines = 1)
            Text(
                meta,
                style = CTFont.caption,
                color = if (action == StickerPackRowAction.RETRY) CTColor.danger else CTColor.textDim,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(CTSpace.xs))
        when (action) {
            StickerPackRowAction.INSTALLING ->
                CircularProgressIndicator(color = CTColor.accent, modifier = Modifier.size(CTIcon.nav))
            StickerPackRowAction.REMOVE ->
                OutlinedButton(onClick = onAction) { Text(stringResource(R.string.sticker_packs_remove_short), color = CTColor.danger) }
            StickerPackRowAction.GET ->
                FilledTonalButton(onClick = onAction) { Text(stringResource(R.string.sticker_get)) }
            StickerPackRowAction.RETRY ->
                FilledTonalButton(onClick = onAction) { Text(stringResource(R.string.retry)) }
        }
    }
}

/** A cover is drawn at 56 dp; decode at about twice that. */
private const val COVER_PX = 128
