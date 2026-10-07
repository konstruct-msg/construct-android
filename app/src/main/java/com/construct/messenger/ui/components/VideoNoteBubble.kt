@file:OptIn(ExperimentalFoundationApi::class)

package com.construct.messenger.ui.components

import android.graphics.BitmapFactory
import android.view.TextureView
import androidx.annotation.OptIn as AndroidOptIn
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.construct.messenger.R
import com.construct.messenger.data.model.MediaItem
import com.construct.messenger.data.repository.MediaUnavailable
import com.construct.messenger.media.VideoNotePlayback
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTFont
import com.construct.messenger.ui.theme.CTIcon
import com.construct.messenger.ui.theme.CTLayout
import com.construct.messenger.ui.theme.CornerRadius
import com.construct.messenger.util.BlurHash
import com.construct.messenger.util.MediaWire

/** What [VideoNotePlayback][com.construct.messenger.media.VideoNotePlayback] says about this note. */
@Immutable
data class VideoNoteUi(
    val expanded: Boolean = false,
    val paused: Boolean = false,
    /** 0…1 while expanded. */
    val progress: Float = 0f,
    val rate: Float = 1f,
)

/** What the bubble forwards; it decides nothing itself. */
@Immutable
class VideoNoteActions(
    /** Expand with sound, or pause / resume the expanded note. */
    val tap: (ByteArray) -> Unit = {},
    val cycleRate: () -> Unit = {},
    /** Fold back if this note is the expanded one: scrolled away, or opening full screen. */
    val collapse: () -> Unit = {},
    val attach: (TextureView) -> Unit = {},
    val detach: (TextureView) -> Unit = {},
)

/**
 * A video note in the transcript: a short video recorded in the chat, shown whole — never cropped
 * to a circle — playing muted on a loop while it is on screen. A tap expands it in place and plays
 * it with sound, the chat still around it; another tap pauses and resumes, the end folds it back.
 * Full screen is a button on the expanded note (and an accessibility action). **Canon:** iOS
 * `VideoNoteBubbleView`; decision `video-notes-are-uncropped-and-expand.md` (revised 2026-10-05).
 *
 * Fetched as soon as it is shown, as a photo is (Android has no auto-download setting; a note is
 * seconds of 720p). Played from memory, like any video here — the decrypted file is never written.
 * The inline loop has its audio track switched off and asks for no audio focus, so a note
 * scrolling past does not pause what the user is listening to.
 */
@Composable
fun VideoNoteBubble(
    item: MediaItem,
    load: suspend (MediaItem) -> ByteArray,
    playback: VideoNoteUi,
    actions: VideoNoteActions,
    /** The width while expanded: [VideoNoteLayout.expandedWidth] of the row. */
    expandedWidth: Dp,
    onOpenFullScreen: () -> Unit,
    onLongPress: () -> Unit,
    onDoubleTap: (() -> Unit)?,
) {
    val width by animateDpAsState(
        targetValue = if (playback.expanded) expandedWidth else VideoNoteLayout.WIDTH,
        animationSpec = spring(),
        label = "video-note-width",
    )
    val height = width / VideoNoteLayout.aspect(item.width, item.height)
    val uploading = item.mediaId.startsWith(MediaWire.LOCAL_PREFIX)
    var bytes by remember(item.mediaId) { mutableStateOf<ByteArray?>(null) }
    var state by remember(item.mediaId) { mutableStateOf(NoteLoad.LOADING) }
    var attempt by remember(item.mediaId) { mutableIntStateOf(0) }
    var firstFrame by remember(item.mediaId) { mutableStateOf(false) }
    /** Tapped before the bytes were here: play once they are (iOS `fetch(thenPlay: true)`). */
    var playWhenLoaded by remember(item.mediaId) { mutableStateOf(false) }
    val currentActions by rememberUpdatedState(actions)
    val label = stringResource(R.string.video_note)
    val fullScreenLabel = stringResource(R.string.video_note_full_screen)

    LaunchedEffect(item.mediaId, attempt, uploading) {
        if (uploading || bytes != null) return@LaunchedEffect
        state = NoteLoad.LOADING
        state = try {
            val data = load(item)
            bytes = data
            if (playWhenLoaded) {
                playWhenLoaded = false
                currentActions.tap(data)
            }
            NoteLoad.READY
        } catch (e: MediaUnavailable) {
            NoteLoad.UNAVAILABLE
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            NoteLoad.FAILED
        }
    }
    // Scrolled out of view: nobody is watching, so stop and fold back (iOS `onDisappear`).
    DisposableEffect(item.mediaId) {
        onDispose { currentActions.collapse() }
    }
    val openFullScreen = {
        if (!uploading && bytes != null) {
            // The full-screen viewer has its own player; two would play over each other.
            actions.collapse()
            onOpenFullScreen()
        }
    }

    Box(
        modifier = Modifier
            .size(width, height)
            .clip(RoundedCornerShape(CornerRadius.control))
            .background(CTColor.bgMsg)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {
                    val data = bytes
                    when {
                        uploading || state == NoteLoad.UNAVAILABLE -> Unit
                        data != null -> actions.tap(data)
                        state == NoteLoad.FAILED -> {
                            playWhenLoaded = true
                            attempt++
                        }
                        else -> playWhenLoaded = true
                    }
                },
                onLongClick = onLongPress,
                onDoubleClick = onDoubleTap,
            )
            .semantics {
                contentDescription = label
                customActions = listOf(CustomAccessibilityAction(fullScreenLabel) { openFullScreen(); true })
            },
        contentAlignment = Alignment.Center,
    ) {
        val poster = remember(item.mediaId) { poster(item) }
        poster?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        if (playback.expanded) {
            ExpandedSurface(actions, Modifier.fillMaxSize())
        } else {
            bytes?.let { data ->
                MutedLoop(
                    data = data,
                    key = item.mediaId,
                    onFirstFrame = { firstFrame = true },
                    // Under the poster until a frame is drawn: no black flash.
                    modifier = Modifier.fillMaxSize().alpha(if (firstFrame) 1f else 0f),
                )
            }
        }
        when {
            uploading || (state == NoteLoad.LOADING && bytes == null) ->
                CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
            state == NoteLoad.UNAVAILABLE ->
                Icon(Icons.Filled.Warning, stringResource(R.string.media_unavailable), tint = CTColor.danger, modifier = Modifier.size(CTIcon.overlay))
            state == NoteLoad.FAILED ->
                Icon(
                    Icons.Filled.Download,
                    stringResource(R.string.retry),
                    tint = Color.White,
                    modifier = Modifier
                        .size(CTIcon.hero)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(CornerRadius.small))
                        .padding(8.dp),
                )
            playback.expanded && playback.paused ->
                Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(CTIcon.overlay))
            else -> Unit
        }
        if (playback.expanded) {
            // Full screen, top left (iOS `fullScreenButton`).
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .size(CTLayout.hitTarget)
                    .clickable(onClickLabel = fullScreenLabel) { openFullScreen() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.OpenInFull,
                    fullScreenLabel,
                    tint = Color.White,
                    modifier = Modifier
                        .size(CTIcon.overlay)
                        .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                        .padding(7.dp),
                )
            }
            // How far along, and the speed (iOS `playingControls`).
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                val speedLabel = stringResource(R.string.playback_speed)
                Box(
                    modifier = Modifier
                        .align(Alignment.End)
                        .sizeIn(minWidth = CTLayout.hitTarget, minHeight = CTLayout.hitTarget)
                        .clickable(onClickLabel = speedLabel) { actions.cycleRate() }
                        .semantics { contentDescription = "$speedLabel ${VideoNotePlayback.rateLabel(playback.rate)}" },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        VideoNotePlayback.rateLabel(playback.rate),
                        style = CTFont.caption,
                        color = Color.White,
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(CornerRadius.badge))
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                    )
                }
                LinearProgressIndicator(
                    progress = { playback.progress },
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = 0.3f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = CTLayout.inlinePad, end = CTLayout.inlinePad, bottom = CTLayout.inlinePad),
                )
            }
        } else if (!uploading) {
            // That the sound is off here, and the length (iOS `chip`).
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(CornerRadius.badge))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.VolumeOff,
                    stringResource(R.string.video_note_muted),
                    tint = Color.White,
                    modifier = Modifier.size(CTIcon.caption),
                )
                item.durationMs?.takeIf { it > 0 }?.let {
                    Text(VideoNoteLayout.duration(it), style = CTFont.caption, color = Color.White)
                }
            }
        }
    }
}

/** The expanded note's picture: the shared player draws into this while the note is expanded. */
@Composable
private fun ExpandedSurface(actions: VideoNoteActions, modifier: Modifier) {
    var view by remember { mutableStateOf<TextureView?>(null) }
    view?.let { v ->
        DisposableEffect(v) {
            actions.attach(v)
            onDispose { actions.detach(v) }
        }
    }
    AndroidView(factory = { ctx -> TextureView(ctx).also { view = it } }, modifier = modifier)
}

private enum class NoteLoad { LOADING, READY, FAILED, UNAVAILABLE }

/** The note's video track on a loop, silent, paused while the app is in the background. */
@AndroidOptIn(UnstableApi::class)
@Composable
private fun MutedLoop(data: ByteArray, key: String, onFirstFrame: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val player = remember(data) {
        ExoPlayer.Builder(context).build().apply {
            // No audio track at all: a muted player that still had one could take the audio
            // session; handleAudioFocus stays false (the default) for the same reason.
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .build()
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ONE
            setMediaSource(
                ProgressiveMediaSource.Factory { ByteArrayDataSource(data) }
                    .createMediaSource(androidx.media3.common.MediaItem.fromUri("data://$key")),
            )
            addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() = onFirstFrame()
            })
            prepare()
            playWhenReady = true
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(player, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> player.pause()
                Lifecycle.Event.ON_RESUME -> player.play()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            player.release()
        }
    }
    // A TextureView, not PlayerView's SurfaceView: it scrolls with the list like any view.
    AndroidView(
        factory = { ctx -> TextureView(ctx).also(player::setVideoTextureView) },
        modifier = modifier,
    )
}

private fun poster(item: MediaItem) =
    item.thumbnail?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() }
        ?: item.blurhash?.let { hash ->
            BlurHash.decode(hash, 24, 32)?.let {
                android.graphics.Bitmap.createBitmap(it, 24, 32, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
            }
        }

/** **Canon:** iOS `ChatUIConstants.VideoNote`. Pure, so the shape is testable. */
object VideoNoteLayout {
    /** Narrower than a photo (260): a note is a message, and at 3:4 it already stands tall. */
    val WIDTH = 200.dp

    /** An expanded note wider than this is a poster, not a message (tablets). */
    val MAX_EXPANDED_WIDTH = 480.dp

    /** The row's opposite side stays free while a note is expanded: the chat is still there. */
    val SIDE_GUTTER = 60.dp

    /** Before the row is measured. */
    val DEFAULT_ROW_WIDTH = 390.dp

    /**
     * Expanded in place while it plays with sound: the row less the opposite side's gutter and
     * the edge padding on both sides, never narrower than the collapsed note nor wider than
     * [MAX_EXPANDED_WIDTH]. [rowWidth] is the whole row; an unmeasured one counts as
     * [DEFAULT_ROW_WIDTH]. **Canon:** iOS `ChatUIConstants.VideoNote.expandedWidth(in:)`.
     */
    fun expandedWidth(rowWidth: Dp): Dp {
        val row = if (rowWidth.value.isFinite() && rowWidth > 0.dp) rowWidth else DEFAULT_ROW_WIDTH
        return minOf(MAX_EXPANDED_WIDTH, maxOf(WIDTH, row - SIDE_GUTTER - CTLayout.edgePad * 2))
    }

    /** The recorded shape; a note from a client that did not record 3:4 keeps its own. */
    const val DEFAULT_ASPECT = 3f / 4f

    fun aspect(width: Int?, height: Int?): Float =
        if (width != null && height != null && width > 0 && height > 0) width.toFloat() / height else DEFAULT_ASPECT

    fun duration(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }
}
