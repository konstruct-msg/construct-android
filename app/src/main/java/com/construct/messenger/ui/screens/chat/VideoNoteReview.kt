package com.construct.messenger.ui.screens.chat

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.construct.messenger.R
import com.construct.messenger.media.VideoNoteTake
import com.construct.messenger.media.VideoNoteTrim
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CornerRadius
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The recording so far, played with sound on a loop over the kept stretch. **Canon:** iOS
 * `TrimmedLoopPlayer`. The segments play as one playlist, each clipped to its part of [range].
 * This one asks for audio focus: it is played on purpose, unlike a note's muted loop.
 */
@OptIn(UnstableApi::class)
@Composable
fun ReviewLoop(segments: List<VideoNoteTake.Segment>, range: LongRange?, modifier: Modifier) {
    val context = LocalContext.current
    val player = remember(segments, range) {
        ExoPlayer.Builder(context).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                /* handleAudioFocus = */ true,
            )
            val clips = VideoNoteTrim.clips(segments.map { it.durationMs }, range)
            setMediaItems(
                clips.map { (i, part) ->
                    MediaItem.Builder()
                        .setUri(android.net.Uri.fromFile(segments[i].file))
                        .setClippingConfiguration(
                            MediaItem.ClippingConfiguration.Builder()
                                .setStartPositionMs(part.first)
                                .setEndPositionMs(part.last)
                                .build(),
                        )
                        .build()
                },
            )
            repeatMode = Player.REPEAT_MODE_ALL
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(factory = { ctx -> TextureView(ctx) }, update = { player.setVideoTextureView(it) }, modifier = modifier)
}

/**
 * Frames of the recording with a handle at each end of the kept stretch. **Canon:** iOS
 * `VideoTrimBar` — what is cut away dimmed, the kept stretch outlined, handles at least
 * [VideoNoteTrim.SHORTEST_MS] apart. [onRange] is called when a handle is let go, not on every
 * point of the drag: the player restarts on each change.
 */
@Composable
fun TrimBar(
    segments: List<VideoNoteTake.Segment>,
    durationMs: Long,
    range: LongRange,
    onRange: (LongRange) -> Unit,
    modifier: Modifier = Modifier,
) {
    var frames by remember(segments) { mutableStateOf<List<ImageBitmap>>(emptyList()) }
    LaunchedEffect(segments) { frames = withContext(Dispatchers.IO) { frames(segments, FRAME_COUNT) } }
    var draft by remember { mutableStateOf<LongRange?>(null) }
    val current by rememberUpdatedState(range)
    val commit by rememberUpdatedState(onRange)
    val startLabel = stringResource(R.string.video_note_trim_start)
    val endLabel = stringResource(R.string.video_note_trim_end)

    BoxWithConstraints(
        modifier
            .height(TRIM_BAR_HEIGHT)
            .clip(RoundedCornerShape(CornerRadius.badge)),
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        val density = LocalDensity.current
        val shown = draft ?: range
        fun x(t: Long): Dp = with(density) { (t.toFloat() / durationMs.coerceAtLeast(1) * widthPx).toDp() }
        fun t(px: Float): Long = (px / widthPx * durationMs).toLong()

        Row(Modifier.fillMaxSize()) {
            frames.forEach {
                Image(it, null, Modifier.weight(1f).fillMaxHeight(), contentScale = ContentScale.Crop)
            }
        }
        // What is cut away, dimmed.
        Box(Modifier.width(x(shown.first)).fillMaxHeight().background(CTColor.mediaGround.copy(alpha = CUT_DIM)))
        Box(
            Modifier
                .offset(x = x(shown.last))
                .width(maxWidth - x(shown.last))
                .fillMaxHeight()
                .background(CTColor.mediaGround.copy(alpha = CUT_DIM)),
        )
        // The kept stretch.
        Box(
            Modifier
                .offset(x = x(shown.first))
                .width((x(shown.last) - x(shown.first)).coerceAtLeast(HANDLE * 2))
                .fillMaxHeight()
                .border(2.dp, CTColor.accent, RoundedCornerShape(CornerRadius.badge)),
        )
        Handle(x(shown.first), startLabel, VideoNoteClock.format(shown.first)) { step ->
            commit(VideoNoteTrim.moved(true, current.first + step, current, durationMs))
        }
        Handle(x(shown.last) - HANDLE, endLabel, VideoNoteClock.format(shown.last)) { step ->
            commit(VideoNoteTrim.moved(false, current.last + step, current, durationMs))
        }
        // One drag surface over the bar: the handle nearer to where the finger lands moves.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(durationMs, widthPx) {
                    var start = true
                    detectHorizontalDragGestures(
                        onDragStart = { at ->
                            val r = current
                            val t0 = t(at.x)
                            start = kotlin.math.abs(t0 - r.first) <= kotlin.math.abs(t0 - r.last)
                            draft = r
                        },
                        onDragEnd = {
                            draft?.let(commit)
                            draft = null
                        },
                        onDragCancel = { draft = null },
                    ) { change, _ ->
                        change.consume()
                        draft = VideoNoteTrim.moved(start, t(change.position.x), draft ?: current, durationMs)
                    }
                },
        )
    }
}

@Composable
private fun Handle(x: Dp, label: String, value: String, onStep: (Long) -> Unit) {
    Box(
        Modifier
            .offset(x = x)
            .width(HANDLE)
            .fillMaxHeight()
            .background(CTColor.accent, RoundedCornerShape(CornerRadius.badge))
            .semantics {
                contentDescription = label
                stateDescription = value
                // iOS: adjustable by half a second.
                customActions = listOf(
                    CustomAccessibilityAction("+0.5 s") { onStep(500); true },
                    CustomAccessibilityAction("−0.5 s") { onStep(-500); true },
                )
            },
    ) {
        Box(
            Modifier
                .padding(vertical = GRIP_INSET)
                .width(2.dp)
                .fillMaxHeight()
                .background(CTColor.bg, RoundedCornerShape(1.dp))
                .align(androidx.compose.ui.Alignment.Center),
        )
    }
}

/** [count] frames spread over the joined segments, each from the middle of its slice. */
private fun frames(segments: List<VideoNoteTake.Segment>, count: Int): List<ImageBitmap> {
    val total = segments.sumOf { it.durationMs }
    if (total <= 0) return emptyList()
    val retrievers = segments.map { s -> MediaMetadataRetriever().also { it.setDataSource(s.file.absolutePath) } }
    try {
        return (0 until count).mapNotNull { k ->
            var t = (total * (k + 0.5) / count).toLong()
            var i = 0
            while (i < segments.lastIndex && t >= segments[i].durationMs) {
                t -= segments[i].durationMs
                i++
            }
            val r = retrievers[i]
            val frame: Bitmap? = if (android.os.Build.VERSION.SDK_INT >= 27) {
                r.getScaledFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, FRAME_PX, FRAME_PX)
            } else {
                r.getFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
            frame?.asImageBitmap()
        }
    } finally {
        retrievers.forEach { it.release() }
    }
}

/** iOS `ChatUIConstants.VideoNote` trim values. */
private val TRIM_BAR_HEIGHT = 44.dp
private val HANDLE = 14.dp
private val GRIP_INSET = 10.dp
private const val FRAME_COUNT = 8
private const val FRAME_PX = 96
private const val CUT_DIM = 0.6f
