package com.construct.messenger.ui.screens.calls

import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.SignalWifiStatusbarConnectedNoInternet4
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.construct.messenger.R
import com.construct.messenger.data.model.CallUi
import com.construct.messenger.data.model.CallVideoFrames
import com.construct.messenger.ui.components.CTAvatar
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTFont
import com.construct.messenger.ui.theme.CTIcon
import com.construct.messenger.ui.theme.CTLayout
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/**
 * The call screen while a camera is on. Like FaceTime, only less: one face on the whole screen,
 * ours in a small window, the controls in one capsule. **Canon:** iOS `VideoCallView`; what goes
 * where is [VideoCallStage].
 */
@Composable
fun VideoCallScreen(
    call: CallUi,
    stage: VideoCallStage,
    status: String,
    frames: CallVideoFrames,
    onSwap: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleCamera: () -> Unit,
    onSwitchCamera: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onEnd: () -> Unit,
    onMinimize: (() -> Unit)?,
) {
    val context = LocalContext.current
    val talkBack = remember {
        context.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true
    }
    val connecting = call.phase != CallUi.Phase.ACTIVE
    var controlsShown by remember { mutableStateOf(true) }
    // Bumped by every touch on the controls: the countdown starts again instead of the controls
    // vanishing under the finger.
    var touches by remember { mutableIntStateOf(0) }
    LaunchedEffect(stage, connecting, controlsShown, touches) {
        if (!stage.controlsAutoHide(connecting, talkBack)) {
            controlsShown = true
            return@LaunchedEffect
        }
        if (controlsShown) {
            delay(VideoCallStage.CONTROLS_HIDE_AFTER_MS)
            controlsShown = false
        }
    }
    var corner by rememberSaveable { mutableStateOf(PreviewCorner.TOP_END) }

    BoxWithConstraints(Modifier.fillMaxSize().background(CTColor.bg)) {
        Pane(
            pane = stage.big,
            call = call,
            frames = frames,
            overlay = false,
            modifier = Modifier
                .fillMaxSize()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { controlsShown = !controlsShown }
                .clearAndSetSemantics {},
        )

        AnimatedVisibility(visible = controlsShown, enter = fadeIn(), exit = fadeOut()) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Header(call, status, onMinimize)
                Spacer(Modifier.weight(1f))
                Controls(
                    call = call,
                    onTouch = { touches++ },
                    onToggleMute = onToggleMute,
                    onToggleCamera = onToggleCamera,
                    onSwitchCamera = onSwitchCamera,
                    onToggleSpeaker = onToggleSpeaker,
                    onEnd = onEnd,
                )
                Spacer(Modifier.height(CAPSULE_BOTTOM))
            }
        }

        stage.small?.let { small ->
            PreviewWindow(
                pane = small,
                call = call,
                frames = frames,
                corner = corner,
                controlsShown = controlsShown,
                canSwap = stage.canSwap,
                onSwap = onSwap,
                onLand = { corner = it },
                screenWidth = constraints.maxWidth.toFloat(),
                screenHeight = constraints.maxHeight.toFloat(),
            )
        }
    }
}

@Composable
private fun Pane(pane: VideoCallStage.Pane, call: CallUi, frames: CallVideoFrames, overlay: Boolean, modifier: Modifier) {
    when (pane) {
        VideoCallStage.Pane.REMOTE_VIDEO -> VideoView(local = false, mirror = false, overlay = overlay, frames = frames, modifier = modifier)
        // The front camera is a mirror, as in every camera app.
        VideoCallStage.Pane.LOCAL_VIDEO -> VideoView(local = true, mirror = call.video.frontCamera, overlay = overlay, frames = frames, modifier = modifier)
        VideoCallStage.Pane.REMOTE_AVATAR -> Box(modifier.background(CTColor.bg), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CTAvatar(userId = call.peerId, displayName = call.peerName, size = 96.dp)
                Spacer(Modifier.height(CTLayout.edgePad))
                Icon(
                    Icons.Filled.VideocamOff,
                    contentDescription = stringResource(R.string.call_peer_camera_off),
                    tint = CTColor.textDim,
                    modifier = Modifier.size(CTIcon.nav),
                )
            }
        }
        VideoCallStage.Pane.LOCAL_CAMERA_OFF -> Box(modifier.background(CTColor.bgMsg), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.VideocamOff, contentDescription = null, tint = CTColor.textDim, modifier = Modifier.size(CTLayout.callIconSize))
        }
    }
}

/**
 * One side's frames, drawn by WebRTC's renderer on the context they were decoded with. The view
 * is a sink of that side for as long as it is on screen, and released after.
 */
@Composable
private fun VideoView(local: Boolean, mirror: Boolean, overlay: Boolean, frames: CallVideoFrames, modifier: Modifier) {
    val context = LocalContext.current
    val renderer = remember(local, overlay) {
        SurfaceViewRenderer(context).apply {
            init(frames.context, null)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            setEnableHardwareScaler(true)
            // The small window draws over the big one; two surfaces otherwise race for the top.
            setZOrderMediaOverlay(overlay)
        }
    }
    DisposableEffect(renderer) {
        frames.add(local, renderer)
        onDispose {
            frames.remove(local, renderer)
            renderer.release()
        }
    }
    AndroidView(factory = { renderer }, update = { it.setMirror(mirror) }, modifier = modifier)
}

@Composable
private fun Header(call: CallUi, status: String, onMinimize: (() -> Unit)?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = CTLayout.edgePad, vertical = CTLayout.inlinePad),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onMinimize != null) {
            Box(
                modifier = Modifier
                    .size(CTLayout.hitTarget)
                    .clip(CircleShape)
                    .background(SCRIM)
                    .clickable(onClick = onMinimize)
                    .semantics { role = Role.Button },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.call_minimize), tint = CTColor.onMedia)
            }
            Spacer(Modifier.width(CTLayout.edgePad))
        }
        Column(Modifier.semantics(mergeDescendants = true) {}) {
            Text(call.peerName, style = CTFont.title, color = CTColor.onMedia)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Lock, contentDescription = stringResource(R.string.call_e2ee_badge), tint = CTColor.onMedia, modifier = Modifier.size(CTIcon.caption))
                Spacer(Modifier.width(5.dp))
                Text(status, style = CTFont.body, color = CTColor.onMedia)
                if (call.reconnecting) {
                    Spacer(Modifier.width(5.dp))
                    Icon(
                        Icons.Filled.SignalWifiStatusbarConnectedNoInternet4,
                        contentDescription = stringResource(R.string.call_reconnecting),
                        tint = CTColor.onMedia,
                        modifier = Modifier.size(CTIcon.caption),
                    )
                }
            }
        }
    }
}

@Composable
private fun Controls(
    call: CallUi,
    onTouch: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleCamera: () -> Unit,
    onSwitchCamera: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onEnd: () -> Unit,
) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(
            modifier = Modifier.clip(CircleShape).background(SCRIM).padding(CAPSULE_PADDING),
            horizontalArrangement = Arrangement.spacedBy(CONTROL_SPACING),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundControl(
                icon = if (call.muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                label = stringResource(if (call.muted) R.string.call_unmute else R.string.call_mute),
                off = call.muted,
            ) { onTouch(); onToggleMute() }
            RoundControl(
                icon = if (call.video.cameraOn) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                label = stringResource(R.string.call_camera),
                off = !call.video.cameraOn,
            ) { onTouch(); onToggleCamera() }
            if (call.video.cameraOn) {
                RoundControl(Icons.Filled.Cameraswitch, stringResource(R.string.call_flip_camera), off = false) { onTouch(); onSwitchCamera() }
            }
            // iOS shows a route picker only when there is a choice; Android keeps its speaker
            // toggle, which turning the camera on has already switched on.
            RoundControl(Icons.Filled.VolumeUp, stringResource(R.string.call_speaker), off = !call.speaker) { onTouch(); onToggleSpeaker() }
            Box(
                modifier = Modifier
                    .size(CTLayout.callControlSize)
                    .clip(CircleShape)
                    .background(CTColor.danger)
                    .clickable(onClick = onEnd)
                    .semantics { role = Role.Button },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.CallEnd, contentDescription = stringResource(R.string.call_end), tint = Color.White, modifier = Modifier.size(CTLayout.callIconSize))
            }
        }
    }
}

/**
 * A round control. Off — muted, camera off — is the filled, inverted state, as in FaceTime: the
 * thing that is unusual is the thing that stands out.
 */
@Composable
private fun RoundControl(icon: ImageVector, label: String, off: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(CTLayout.callControlSize)
            .clip(CircleShape)
            .background(if (off) CTColor.mediaControlOn else CTColor.mediaControl)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = label
                role = Role.Button
                selected = off
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (off) Color.Black else CTColor.onMedia, modifier = Modifier.size(CTLayout.callIconSize))
    }
}

/** The small window: dragged anywhere, lands in the nearest corner, a tap swaps the faces. */
@Composable
private fun PreviewWindow(
    pane: VideoCallStage.Pane,
    call: CallUi,
    frames: CallVideoFrames,
    corner: PreviewCorner,
    controlsShown: Boolean,
    canSwap: Boolean,
    onSwap: () -> Unit,
    onLand: (PreviewCorner) -> Unit,
    screenWidth: Float,
    screenHeight: Float,
) {
    val density = LocalDensity.current
    val w = with(density) { PREVIEW_WIDTH.toPx() }
    val h = with(density) { PREVIEW_HEIGHT.toPx() }
    val inset = with(density) { CTLayout.edgePad.toPx() }
    // Clear of the header and the capsule while they are shown, in the window's corner.
    val reservedTop = with(density) { if (controlsShown && corner.isTop) HEADER_CLEARANCE.toPx() else 0f }
    val reservedBottom = with(density) { if (controlsShown && !corner.isTop) CAPSULE_CLEARANCE.toPx() else 0f }
    val statusBar = WindowInsets.statusBars.getTop(density).toFloat()
    val home = Offset(
        x = if (corner.isStart) inset else screenWidth - inset - w,
        y = if (corner.isTop) statusBar + inset + reservedTop else screenHeight - inset - h - reservedBottom,
    )
    var drag by remember { mutableStateOf(Offset.Zero) }
    val shown by animateOffsetAsState(home, spring(), label = "preview")
    val label = stringResource(if (canSwap) R.string.call_swap_video else R.string.call_self_view)
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .offset { IntOffset((shown.x + drag.x).roundToInt(), (shown.y + drag.y).roundToInt()) }
            .size(PREVIEW_WIDTH, PREVIEW_HEIGHT)
            .shadow(12.dp, shape)
            .clip(shape)
            .border(1.dp, CTColor.mediaControl, shape)
            .pointerInput(corner, screenWidth, screenHeight) {
                detectDragGestures(
                    onDrag = { change, amount ->
                        change.consume()
                        drag += amount
                    },
                    onDragEnd = {
                        val center = home + drag + Offset(w / 2, h / 2)
                        onLand(PreviewCorner.nearest(center.x, center.y, screenWidth, screenHeight))
                        drag = Offset.Zero
                    },
                    onDragCancel = { drag = Offset.Zero },
                )
            }
            .clickable(enabled = canSwap, onClick = onSwap)
            .semantics {
                contentDescription = label
                if (canSwap) role = Role.Button
            },
    ) {
        Pane(pane = pane, call = call, frames = frames, overlay = true, modifier = Modifier.fillMaxSize())
    }
}

private val SCRIM = Color.Black.copy(alpha = 0.35f)
private val PREVIEW_WIDTH = 104.dp
private val PREVIEW_HEIGHT = 156.dp
private val CONTROL_SPACING = 14.dp
private val CAPSULE_PADDING = 10.dp
private val CAPSULE_BOTTOM = 42.dp
private val HEADER_CLEARANCE = CTLayout.hitTarget + CTLayout.inlinePad * 2
private val CAPSULE_CLEARANCE = CTLayout.callControlSize + CAPSULE_PADDING * 2 + CAPSULE_BOTTOM + 24.dp
