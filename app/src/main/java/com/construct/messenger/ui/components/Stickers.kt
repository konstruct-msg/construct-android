@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.construct.messenger.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.construct.messenger.R
import com.construct.messenger.stickers.StickerPack
import com.construct.messenger.stickers.StickerReference
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTFont
import com.construct.messenger.ui.theme.CTIcon
import com.construct.messenger.ui.theme.CTLayout
import com.construct.messenger.ui.theme.CTSpace
import com.construct.messenger.viewmodel.StickersViewModel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** iOS `ChatUIConstants.Sticker.size`: the 512 canvas shown at about a third. */
private val STICKER_SIZE = 160.dp

/** A sticker's picture, decoded off the main thread at the size it is drawn. */
@Composable
internal fun rememberSticker(file: File?, targetPx: Int): ImageBitmap? =
    produceState<ImageBitmap?>(null, file?.path) {
        value = file?.let {
            withContext(Dispatchers.IO) {
                runCatching {
                    val options = BitmapFactory.Options().apply { inSampleSize = if (targetPx <= 128) 4 else 2 }
                    BitmapFactory.decodeFile(it.path, options)?.asImageBitmap()
                }.getOrNull()
            }
        }
    }.value

/**
 * A sticker in the transcript. **Canon:** iOS `StickerBubbleView` — no bubble around it, 160;
 * with its pack absent, the emoji the reference carries, large: the message did arrive, so this is
 * not a failure, and the picture replaces it when the pack lands.
 */
@Composable
fun StickerBubble(
    ref: StickerReference,
    file: File?,
    onMissing: () -> Unit,
    onLongPress: () -> Unit,
    onDoubleTap: () -> Unit,
) {
    LaunchedEffect(ref, file == null) { if (file == null) onMissing() }
    val label = "${ref.emoji} ${stringResource(R.string.sticker)}"
    val image = rememberSticker(file, targetPx = 256)
    Box(
        modifier = Modifier
            .size(STICKER_SIZE)
            .semantics { contentDescription = label }
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
                onLongClick = onLongPress,
                onDoubleClick = onDoubleTap,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(image, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(STICKER_SIZE))
        } else {
            Text(ref.emoji, fontSize = 96.sp)
        }
    }
}

/**
 * The sticker picker: Add stickers, then Recent, then each installed pack (long press on its
 * title removes it from here, never from disk). Add stickers opens [StickerPacksSheet], where
 * packs are added from the catalog and removed. A tap sends at once and closes the sheet: no
 * caption, no composer strip.
 */
@Composable
fun StickerPickerSheet(
    stickers: StickersViewModel,
    onSend: (StickerReference) -> Unit,
    onDismiss: () -> Unit,
) {
    val picker by stickers.picker.collectAsStateWithLifecycle()
    var showPacks by remember { mutableStateOf(false) }
    fun send(ref: StickerReference) {
        onSend(ref)
        onDismiss()
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = CTColor.bg) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = Modifier.fillMaxWidth().padding(horizontal = CTLayout.edgePad),
        ) {
            header("add") {
                FilledTonalButton(onClick = { showPacks = true }, modifier = Modifier.padding(top = CTSpace.s)) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(CTIcon.row))
                    Spacer(Modifier.width(CTSpace.s))
                    Text(stringResource(R.string.sticker_packs_add))
                }
            }
            if (picker.recents.isNotEmpty()) {
                header("recent") { SectionTitle(stringResource(R.string.sticker_picker_recent)) }
                items(picker.recents, key = { "r-${it.packHex}-${it.index}" }) { ref ->
                    StickerCell(stickers.file(ref), ref.emoji) { send(ref) }
                }
            }
            picker.packs.forEach { pack ->
                header("p-${pack.hex}") { PackTitle(pack, onRemove = { stickers.uninstall(pack) }) }
                items(pack.stickers.indices.toList(), key = { "s-${pack.hex}-$it" }) { i ->
                    val ref = pack.reference(i)
                    StickerCell(stickers.file(pack.stickers[i]), pack.stickers[i].emoji) { ref?.let(::send) }
                }
            }
            if (picker.packs.isEmpty() && picker.recents.isEmpty()) {
                header("empty") {
                    Column(Modifier.fillMaxWidth().padding(vertical = CTSpace.xl), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.EmojiEmotions, null, tint = CTColor.textDim, modifier = Modifier.size(CTIcon.overlay))
                        Text(stringResource(R.string.sticker_catalog_empty), style = CTFont.body, color = CTColor.textDim)
                    }
                }
            }
            header("bottom") { Spacer(Modifier.height(CTSpace.xl)) }
        }
    }
    if (showPacks) StickerPacksSheet(stickers, onDismiss = { showPacks = false })
}

private fun androidx.compose.foundation.lazy.grid.LazyGridScope.header(key: String, content: @Composable () -> Unit) =
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }

@Composable
internal fun SectionTitle(text: String) {
    Text(
        text = "> " + text.uppercase(),
        style = CTFont.ui(11, FontWeight.Medium),
        color = CTColor.textDim,
        letterSpacing = 2.sp,
        modifier = Modifier.padding(top = CTSpace.m, bottom = 6.dp),
    )
}

@Composable
private fun PackTitle(pack: StickerPack, onRemove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Text(
            text = "> " + pack.title.uppercase(),
            style = CTFont.ui(11, FontWeight.Medium),
            color = CTColor.textDim,
            letterSpacing = 2.sp,
            modifier = Modifier
                .combinedClickable(onClick = {}, onLongClick = { menu = true })
                .padding(top = CTSpace.m, bottom = 6.dp),
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.sticker_pack_remove), style = CTFont.ui(14), color = CTColor.danger) },
                onClick = {
                    menu = false
                    onRemove()
                },
            )
        }
    }
}

/** iOS: 4 across, a 96 thumbnail, the emoji while the file is not decoded. */
@Composable
private fun StickerCell(file: File?, emoji: String, onTap: () -> Unit) {
    val image = rememberSticker(file, targetPx = 128)
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .padding(6.dp)
            .clickable(onClick = onTap)
            .semantics { contentDescription = emoji },
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) Image(image, null, contentScale = ContentScale.Fit) else Text(emoji, fontSize = 40.sp)
    }
}
