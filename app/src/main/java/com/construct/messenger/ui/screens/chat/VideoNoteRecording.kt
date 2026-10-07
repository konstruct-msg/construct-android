package com.construct.messenger.ui.screens.chat

import androidx.activity.compose.BackHandler
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowCircleUp
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.construct.messenger.R
import com.construct.messenger.media.VideoNoteRecorder
import com.construct.messenger.media.VideoNoteTake
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTFont
import com.construct.messenger.ui.theme.CTIcon
import com.construct.messenger.ui.theme.CornerRadius
import kotlinx.coroutines.launch

/**
 * Recording a video note, over the chat. **Canon:** iOS `VideoNoteRecordingView` — the chat dimmed
 * behind (you see whom you are recording for), the 3:4 viewfinder in the middle, and in the
 * composer's place a bar with cancel, the timer, pause/resume, camera switch and send. Recording
 * starts as soon as the camera is up; it stops by itself at 60 s.
 *
 * Paused (or stopped at 60 s), the viewfinder gives way to the recording so far, playing with sound
 * on a loop over the kept stretch, with a strip of frames and a handle at each end to trim it
 * (iOS `VideoNoteReviewView`). The send keeps that stretch; resuming drops the trim.
 *
 * Shown only once the screen holds CAMERA and RECORD_AUDIO.
 */
@Composable
fun VideoNoteRecordingOverlay(onSend: (VideoNoteTake) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val recorder = remember { VideoNoteRecorder(context.applicationContext) }
    val phase by recorder.phase.collectAsState()
    val elapsed by recorder.elapsedMs.collectAsState()
    val segments by recorder.segments.collectAsState()
    // The kept stretch of the joined recording; null is all of it. Recording again drops it.
    var trim by remember { mutableStateOf<LongRange?>(null) }
    LaunchedEffect(phase) { if (phase == VideoNoteRecorder.Phase.RECORDING) trim = null }
    val reviewing = (phase == VideoNoteRecorder.Phase.PAUSED || phase == VideoNoteRecorder.Phase.LIMIT) && segments.isNotEmpty()
    val scope = rememberCoroutineScope()
    var handedOver by remember { mutableStateOf(false) }
    // Read in the activity's window: inside the dialog the navigation bar's inset arrives as 0
    // and the bar sat under the gesture handle (emulator, 2026-10-04).
    val navBottom = with(androidx.compose.ui.platform.LocalDensity.current) {
        WindowInsets.navigationBars.getBottom(this).toDp()
    }

    DisposableEffect(recorder) { onDispose { if (!handedOver) recorder.cancel() } }
    BackHandler { onClose() }

    fun send() {
        if (handedOver) return
        handedOver = true
        scope.launch {
            recorder.finish()?.let { onSend(it.copy(trimMs = trim)) }
            onClose()
        }
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(CTColor.mediaGround.copy(alpha = RECORDING_DIM))
                // Nothing behind is touchable while recording.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val width = minOf(VIEWFINDER_MAX_WIDTH, maxWidth - 32.dp)
                Box(
                    Modifier
                        .width(width)
                        .aspectRatio(3f / 4f)
                        .clip(RoundedCornerShape(CornerRadius.control))
                        .background(CTColor.mediaGround),
                    contentAlignment = Alignment.Center,
                ) {
                    if (phase == VideoNoteRecorder.Phase.FAILED) {
                        Text(
                            stringResource(R.string.video_note_camera_unavailable),
                            style = CTFont.body,
                            color = CTColor.onMedia,
                            modifier = Modifier.padding(16.dp),
                        )
                    } else {
                        AndroidView(
                            factory = { ctx ->
                                // The centre 3:4 of the 4:3 frames — what the send keeps.
                                PreviewView(ctx).apply {
                                    scaleType = PreviewView.ScaleType.FILL_CENTER
                                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                                    recorder.start(owner, surfaceProvider)
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                        // Over the viewfinder, which stays bound: resuming needs no new camera.
                        if (reviewing) ReviewLoop(segments, trim, Modifier.fillMaxSize())
                    }
                }
                if (reviewing) {
                    val total = segments.sumOf { it.durationMs }
                    TrimBar(
                        segments = segments,
                        durationMs = total,
                        range = trim ?: (0L..total),
                        onRange = { trim = it.takeUnless { r -> r.first == 0L && r.last == total } },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .width(width)
                            .padding(bottom = 12.dp),
                    )
                }
            }
            RecordingBar(
                phase = phase,
                elapsedMs = elapsed,
                onCancel = onClose,
                onPause = recorder::pause,
                onResume = recorder::resume,
                onSwitch = recorder::switchCamera,
                onSend = ::send,
            )
            Spacer(Modifier.fillMaxWidth().height(navBottom).background(CTColor.bg))
        }
    }
}

@Composable
private fun RecordingBar(
    phase: VideoNoteRecorder.Phase,
    elapsedMs: Long,
    onCancel: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onSwitch: () -> Unit,
    onSend: () -> Unit,
) {
    val live = phase == VideoNoteRecorder.Phase.RECORDING
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CTColor.bg)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconButton(onClick = onCancel) {
            Icon(Icons.Filled.Close, stringResource(R.string.video_note_cancel), tint = CTColor.textDim)
        }
        if (live) {
            Box(Modifier.size(8.dp).background(CTColor.danger, CircleShape))
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text = when (phase) {
                VideoNoteRecorder.Phase.PAUSED -> stringResource(R.string.video_note_paused)
                else -> VideoNoteClock.format(elapsedMs)
            },
            style = CTFont.ui(14),
            color = CTColor.text,
        )
        Spacer(Modifier.weight(1f))
        when (phase) {
            VideoNoteRecorder.Phase.RECORDING -> IconButton(onClick = onPause) {
                Icon(Icons.Filled.Pause, stringResource(R.string.video_note_pause), tint = CTColor.text)
            }
            VideoNoteRecorder.Phase.PAUSED -> IconButton(onClick = onResume) {
                Icon(Icons.Filled.FiberManualRecord, stringResource(R.string.video_note_resume), tint = CTColor.danger)
            }
            else -> Unit
        }
        if (phase == VideoNoteRecorder.Phase.RECORDING || phase == VideoNoteRecorder.Phase.PAUSED) {
            IconButton(onClick = onSwitch) {
                Icon(Icons.Filled.Cameraswitch, stringResource(R.string.video_note_switch_camera), tint = CTColor.text)
            }
        }
        val canSend = phase == VideoNoteRecorder.Phase.RECORDING || phase == VideoNoteRecorder.Phase.PAUSED ||
            phase == VideoNoteRecorder.Phase.LIMIT
        IconButton(onClick = onSend, enabled = canSend) {
            Icon(
                Icons.Filled.ArrowCircleUp,
                stringResource(R.string.video_note_send),
                tint = if (canSend) CTColor.accent else CTColor.textDim,
                modifier = Modifier.size(CTIcon.overlay),
            )
        }
    }
}

/** The bar's timer. Pure. */
object VideoNoteClock {
    fun format(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }
}

/** iOS `ChatUIConstants.VideoNote.recordingDim` / `viewfinderMaxWidth`. */
private const val RECORDING_DIM = 0.6f
private val VIEWFINDER_MAX_WIDTH = 320.dp
