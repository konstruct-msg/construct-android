@file:OptIn(ExperimentalFoundationApi::class)

package com.construct.messenger.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.compose.AsyncImagePainter
import com.construct.messenger.R
import com.construct.messenger.data.model.MediaItem
import com.construct.messenger.data.model.MessageMedia
import com.construct.messenger.data.repository.MediaUnavailable
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CornerRadius
import com.construct.messenger.ui.theme.ctRegular
import com.construct.messenger.util.BlurHash

/**
 * Photos and videos of one message. **Canon:** iOS `MediaMessageView` — one item 260 wide at its
 * own aspect, clamped between 2:3 and 3:2 (3:4 when unknown); several as a 244-wide mosaic of
 * square tiles 2 apart (2 side by side, 3 as a big tile and two stacked, 4 as 2×2, 5+ as a hero
 * and pairs, a last odd one full width); rounded 10 as a whole, no bubble around it.
 *
 * A tile shows the sender's BlurHash (or thumbnail) until the picture is fetched and opened.
 * Videos show their poster and length; playing them is not here yet.
 */
@Composable
fun MediaAlbumView(
    album: MessageMedia.Album,
    onOpen: (Int) -> Unit,
    onLongPress: () -> Unit,
) {
    val items = album.items
    val shape = RoundedCornerShape(CornerRadius.control)
    val box = Modifier.clip(shape)
    if (items.size == 1) {
        val item = items[0]
        val width = SINGLE_WIDTH
        Tile(item, width, width / aspect(item), box, { onOpen(0) }, onLongPress)
        return
    }
    val w = ALBUM_WIDTH
    val half = (w - GAP) / 2
    @Composable
    fun tile(i: Int, tw: Dp, th: Dp) = Tile(items[i], tw, th, Modifier, { onOpen(i) }, onLongPress)
    Column(box.width(w), verticalArrangement = Arrangement.spacedBy(GAP)) {
        when (items.size) {
            2 -> Row(horizontalArrangement = Arrangement.spacedBy(GAP)) { tile(0, half, half); tile(1, half, half) }
            3 -> {
                val bigW = (w - GAP) * 0.64f
                val smallW = w - GAP - bigW
                Row(horizontalArrangement = Arrangement.spacedBy(GAP)) {
                    tile(0, bigW, bigW)
                    Column(verticalArrangement = Arrangement.spacedBy(GAP)) {
                        tile(1, smallW, (bigW - GAP) / 2)
                        tile(2, smallW, (bigW - GAP) / 2)
                    }
                }
            }
            4 -> {
                Row(horizontalArrangement = Arrangement.spacedBy(GAP)) { tile(0, half, half); tile(1, half, half) }
                Row(horizontalArrangement = Arrangement.spacedBy(GAP)) { tile(2, half, half); tile(3, half, half) }
            }
            else -> {
                tile(0, w, w * 0.72f)
                tailRows(items.size, 1).forEach { row ->
                    if (row.size == 2) {
                        Row(horizontalArrangement = Arrangement.spacedBy(GAP)) { tile(row[0], half, half); tile(row[1], half, half) }
                    } else {
                        tile(row[0], w, half)
                    }
                }
            }
        }
    }
}

/** iOS `MediaAlbumGridLayout.tailRows`: pairs, and a leftover last index on its own. */
internal fun tailRows(count: Int, start: Int): List<List<Int>> =
    (start until count).chunked(2)

/** iOS `MediaPreviewLayout.clampedAspectRatio`. */
internal fun aspect(item: MediaItem): Float {
    val w = item.width ?: return DEFAULT_ASPECT
    val h = item.height ?: return DEFAULT_ASPECT
    return (w.toFloat() / h).coerceIn(MIN_ASPECT, MAX_ASPECT)
}

@Composable
private fun Tile(
    item: MediaItem,
    width: Dp,
    height: Dp,
    modifier: Modifier,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val preview = rememberPreview(item)
    var attempt by remember { mutableIntStateOf(0) }
    Box(
        modifier = modifier
            .size(width, height)
            .background(CTColor.bgMsg)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onTap,
                onLongClick = onLongPress,
            ),
        contentAlignment = Alignment.Center,
    ) {
        preview?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        if (item.isVideo) {
            // Not fetched in the list: iOS downloads a video only when it is opened.
            Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(40.dp))
            item.durationMs?.let { ms ->
                Text(
                    text = formatDuration(ms),
                    style = ctRegular(11),
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(CornerRadius.badge))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            return@Box
        }
        key(attempt) {
            SubcomposeAsyncImage(
                model = item,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            ) {
                when (val state = painter.state) {
                    is AsyncImagePainter.State.Success -> SubcomposeAsyncImageContent()
                    is AsyncImagePainter.State.Error ->
                        if (state.result.throwable is MediaUnavailable) {
                            Failure(stringResource(R.string.media_unavailable), onRetry = null)
                        } else {
                            Failure(stringResource(R.string.failed_to_load), onRetry = { attempt++ })
                        }
                    else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun Failure(label: String, onRetry: (() -> Unit)?) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CTColor.bgMsg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.Warning, null, tint = CTColor.danger, modifier = Modifier.size(32.dp))
        Spacer(Modifier.height(6.dp))
        Text(label, style = ctRegular(11), color = CTColor.textDim)
        if (onRetry != null) {
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .clickable(onClick = onRetry)
                    .background(CTColor.accent.copy(alpha = 0.1f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Refresh, null, tint = CTColor.accent, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.retry), style = ctRegular(11), color = CTColor.accent)
            }
        }
    }
}

/** The sender's BlurHash at 32 px, else their thumbnail; decoded once per item. */
@Composable
private fun rememberPreview(item: MediaItem): ImageBitmap? = remember(item.mediaId) {
    item.blurhash?.let { hash ->
        BlurHash.decode(hash, PREVIEW_PX, PREVIEW_PX)?.let {
            Bitmap.createBitmap(it, PREVIEW_PX, PREVIEW_PX, Bitmap.Config.ARGB_8888).asImageBitmap()
        }
    } ?: item.thumbnail?.let { bytes ->
        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()
    }
}

/**
 * Full screen, one page per photo, pinch to zoom, "n / N" at the top. **Canon:** iOS
 * `MediaGalleryViewer` (paged, zoomable, counter); saving and sharing are not here yet.
 */
@Composable
fun MediaViewer(album: MessageMedia.Album, startIndex: Int, onDismiss: () -> Unit) {
    val photos = album.items.filter { it.isImage }
    if (photos.isEmpty()) return
    val first = photos.indexOf(album.items.getOrNull(startIndex)).coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = first) { photos.size }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                ZoomableImage(photos[page])
            }
            Row(
                modifier = Modifier.statusBarsPadding().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close), tint = Color.White)
                }
                if (photos.size > 1) {
                    Text(
                        stringResource(R.string.media_viewer_counter, pager.currentPage + 1, photos.size),
                        style = ctRegular(14),
                        color = Color.White,
                    )
                }
            }
        }
    }
}

@Composable
private fun ZoomableImage(item: MediaItem) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val state = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 5f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }
    SubcomposeAsyncImage(
        model = item,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(item.mediaId) {
                detectTapGestures(onDoubleTap = {
                    scale = if (scale > 1f) 1f else 2.5f
                    offset = Offset.Zero
                })
            }
            .transformable(state, canPan = { scale > 1f })
            .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
        loading = {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp)
            }
        },
    )
}

private fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

private val SINGLE_WIDTH = 260.dp
private val ALBUM_WIDTH = 244.dp
private val GAP = 2.dp
private const val PREVIEW_PX = 32
private const val MIN_ASPECT = 2f / 3f
private const val MAX_ASPECT = 3f / 2f
private const val DEFAULT_ASPECT = 3f / 4f
