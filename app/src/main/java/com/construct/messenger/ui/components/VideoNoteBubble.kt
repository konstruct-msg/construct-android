@file:OptIn(ExperimentalFoundationApi::class)

package com.construct.messenger.ui.components

import android.graphics.BitmapFactory
import android.view.TextureView
import androidx.annotation.OptIn as AndroidOptIn
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CornerRadius
import com.construct.messenger.ui.theme.ctRegular
import com.construct.messenger.util.BlurHash
import com.construct.messenger.util.MediaWire

/**
 * A video note in the transcript: a short video recorded in the chat, shown whole — never cropped
 * to a circle — playing muted on a loop while it is on screen, and opening full screen with sound
 * on tap. **Canon:** iOS `VideoNoteBubbleView`; decision `video-notes-are-uncropped-and-expand.md`.
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
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onDoubleTap: (() -> Unit)?,
) {
    val width = VideoNoteLayout.WIDTH
    val height = width / VideoNoteLayout.aspect(item.width, item.height)
    val uploading = item.mediaId.startsWith(MediaWire.LOCAL_PREFIX)
    var bytes by remember(item.mediaId) { mutableStateOf<ByteArray?>(null) }
    var state by remember(item.mediaId) { mutableStateOf(NoteLoad.LOADING) }
    var attempt by remember(item.mediaId) { mutableIntStateOf(0) }
    var firstFrame by remember(item.mediaId) { mutableStateOf(false) }
    val label = stringResource(R.string.video_note)

    LaunchedEffect(item.mediaId, attempt, uploading) {
        if (uploading || bytes != null) return@LaunchedEffect
        state = NoteLoad.LOADING
        state = try {
            bytes = load(item)
            NoteLoad.READY
        } catch (e: MediaUnavailable) {
            NoteLoad.UNAVAILABLE
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            NoteLoad.FAILED
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
                    when {
                        uploading || state == NoteLoad.UNAVAILABLE -> Unit
                        state == NoteLoad.FAILED -> attempt++
                        else -> onOpen()
                    }
                },
                onLongClick = onLongPress,
                onDoubleClick = onDoubleTap,
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        val poster = remember(item.mediaId) { poster(item) }
        poster?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        bytes?.let { data ->
            MutedLoop(
                data = data,
                key = item.mediaId,
                onFirstFrame = { firstFrame = true },
                // Under the poster until a frame is drawn: no black flash.
                modifier = Modifier.fillMaxSize().alpha(if (firstFrame) 1f else 0f),
            )
        }
        when {
            uploading || (state == NoteLoad.LOADING && bytes == null) ->
                CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
            state == NoteLoad.UNAVAILABLE ->
                Icon(Icons.Filled.Warning, stringResource(R.string.media_unavailable), tint = CTColor.danger, modifier = Modifier.size(28.dp))
            state == NoteLoad.FAILED ->
                Icon(
                    Icons.Filled.Download,
                    stringResource(R.string.retry),
                    tint = Color.White,
                    modifier = Modifier
                        .size(44.dp)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(CornerRadius.small))
                        .padding(8.dp),
                )
            else -> Unit
        }
        if (!uploading) {
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
                    modifier = Modifier.size(10.dp),
                )
                item.durationMs?.takeIf { it > 0 }?.let {
                    Text(VideoNoteLayout.duration(it), style = ctRegular(11), color = Color.White)
                }
            }
        }
    }
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

    /** The recorded shape; a note from a client that did not record 3:4 keeps its own. */
    const val DEFAULT_ASPECT = 3f / 4f

    fun aspect(width: Int?, height: Int?): Float =
        if (width != null && height != null && width > 0 && height > 0) width.toFloat() / height else DEFAULT_ASPECT

    fun duration(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }
}
