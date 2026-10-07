package com.construct.messenger.ui.screens.calls

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.construct.messenger.R
import com.construct.messenger.data.model.CallUi
import com.construct.messenger.data.model.CallVideoFrames
import com.construct.messenger.ui.components.CTAvatar
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTFont
import com.construct.messenger.ui.theme.CTIcon
import com.construct.messenger.ui.theme.CTLayout
import kotlinx.coroutines.delay

/**
 * The call, full screen. **Canon:** iOS `InCallView` — one focal point (the avatar, pulsing while
 * the call connects or reconnects), the name, a status line, the end-to-end badge, and one row of
 * controls with the red end button under it. An incoming call that is still ringing has answer and
 * decline instead: on iOS CallKit draws those; a self-managed Android call draws its own.
 */
@Composable
fun CallScreen(
    call: CallUi,
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
    onEnd: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onMinimize: () -> Unit,
    frames: CallVideoFrames? = null,
    onToggleCamera: () -> Unit = {},
    onSwitchCamera: () -> Unit = {},
) {
    // Which face is on the big screen when both cameras are on.
    var swapped by rememberSaveable { mutableStateOf(false) }
    val stage = VideoCallStage.make(
        call.video,
        isConnecting = call.phase != CallUi.Phase.ACTIVE,
        isEnded = !call.isLive,
        swapped = swapped,
    )
    // A ringing call is answered here; the video screen begins once it is.
    if (stage != null && frames != null && call.phase != CallUi.Phase.INCOMING) {
        VideoCallScreen(
            call = call,
            stage = stage,
            status = statusText(call),
            frames = frames,
            onSwap = { swapped = !swapped },
            onToggleMute = onToggleMute,
            onToggleCamera = onToggleCamera,
            onSwitchCamera = onSwitchCamera,
            onToggleSpeaker = onToggleSpeaker,
            onEnd = onEnd,
            onMinimize = onMinimize,
        )
        return
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CTColor.bg)
            .statusBarsPadding()
            .navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = CTLayout.edgePad, vertical = CTLayout.inlinePad)) {
            if (call.isLive) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.call_minimize),
                    tint = CTColor.textDim,
                    modifier = Modifier.size(CTLayout.hitTarget).clip(CircleShape).clickable(onClick = onMinimize).padding(10.dp),
                )
            }
        }
        Spacer(Modifier.weight(1f))

        Box(contentAlignment = Alignment.Center) {
            val pulsing = call.isLive && (call.phase != CallUi.Phase.ACTIVE || call.reconnecting)
            if (pulsing) PulseRing(size = AVATAR)
            CTAvatar(userId = call.peerId, displayName = call.peerName, size = AVATAR)
        }
        Spacer(Modifier.height(16.dp))
        Text(call.peerName, style = CTFont.ui(22, FontWeight.Bold), color = CTColor.text)
        Spacer(Modifier.height(8.dp))
        Text(
            text = statusText(call),
            style = CTFont.ui(14),
            color = if (call.isLive) CTColor.textDim else CTColor.danger.copy(alpha = 0.85f),
        )
        if (call.isLive) {
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.background(CTColor.bgMsg, CircleShape).padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Lock, contentDescription = null, tint = CTColor.textDim, modifier = Modifier.size(CTIcon.caption))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.call_e2ee_badge), style = CTFont.caption, color = CTColor.textDim)
            }
        }

        Spacer(Modifier.weight(2f))
        when {
            !call.isLive -> Spacer(Modifier.height(CTLayout.callEndSize + 52.dp))
            call.phase == CallUi.Phase.INCOMING -> Row(
                horizontalArrangement = Arrangement.spacedBy(72.dp),
                modifier = Modifier.padding(bottom = 52.dp),
            ) {
                RoundAction(Icons.Filled.CallEnd, stringResource(R.string.call_decline), CTColor.danger, onDecline)
                RoundAction(Icons.Filled.Call, stringResource(R.string.call_answer), CTColor.accent, onAnswer)
            }
            else -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(bottom = 52.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(36.dp)) {
                    Control(
                        icon = if (call.muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                        label = stringResource(if (call.muted) R.string.call_unmute else R.string.call_mute),
                        on = call.muted,
                        onClick = onToggleMute,
                    )
                    // Turning the camera on here is what moves the call to the video screen.
                    if (call.video.canSend && frames != null) {
                        Control(
                            icon = if (call.video.cameraOn) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                            label = stringResource(R.string.call_camera),
                            on = call.video.cameraOn,
                            onClick = onToggleCamera,
                        )
                    }
                    Control(
                        icon = Icons.Filled.VolumeUp,
                        label = stringResource(R.string.call_speaker),
                        on = call.speaker,
                        onClick = onToggleSpeaker,
                    )
                }
                Spacer(Modifier.height(28.dp))
                RoundAction(Icons.Filled.CallEnd, stringResource(R.string.call_end), CTColor.danger, onEnd, showLabel = false)
            }
        }
    }
}

@Composable
private fun statusText(call: CallUi): String {
    call.ended?.let {
        return stringResource(
            when (it) {
                CallUi.EndedAs.ENDED -> R.string.call_ended
                CallUi.EndedAs.DECLINED -> R.string.call_declined
                CallUi.EndedAs.BUSY -> R.string.call_busy
                CallUi.EndedAs.MISSED -> R.string.call_missed
                CallUi.EndedAs.FAILED -> R.string.call_failed
            },
        )
    }
    return when (call.phase) {
        CallUi.Phase.INCOMING -> stringResource(if (call.isVideoCall) R.string.call_incoming_video else R.string.call_incoming_audio)
        CallUi.Phase.CALLING, CallUi.Phase.RINGING -> stringResource(R.string.call_status_calling)
        CallUi.Phase.CONNECTING -> stringResource(R.string.call_connecting)
        CallUi.Phase.ACTIVE -> if (call.reconnecting) stringResource(R.string.call_reconnecting) else elapsed(call.activeSinceMs)
        CallUi.Phase.ENDED -> stringResource(R.string.call_ended)
    }
}

/** mm:ss since [sinceMs], ticking. */
@Composable
fun elapsed(sinceMs: Long?): String {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(sinceMs) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val seconds = ((now - (sinceMs ?: now)) / 1000).coerceAtLeast(0)
    return "%02d:%02d".format(seconds / 60, seconds % 60)
}

/** iOS `CallControlButton`: a disc with a glyph and a word under it; accent while on. */
@Composable
private fun Control(icon: ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    val tint = if (on) CTColor.accent else CTColor.textDim
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(CTLayout.callControlSize)
                .clip(CircleShape)
                .background(CTColor.bgMsg)
                .border(1.dp, tint.copy(alpha = 0.4f), CircleShape)
                .clickable(onClick = onClick)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(CTLayout.callIconSize))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = CTFont.micro, color = CTColor.textDim)
    }
}

@Composable
private fun RoundAction(icon: ImageVector, label: String, color: Color, onClick: () -> Unit, showLabel: Boolean = true) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(CTLayout.callEndSize)
                .clip(CircleShape)
                .background(color)
                .clickable(onClick = onClick)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(CTLayout.callIconSize))
        }
        if (showLabel) {
            Spacer(Modifier.height(6.dp))
            Text(label, style = CTFont.micro, color = CTColor.textDim)
        }
    }
}

/** iOS `PulseRingView`: a ring that grows from the avatar and fades, over and over. */
@Composable
private fun PulseRing(size: androidx.compose.ui.unit.Dp) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "pulse",
    )
    val accent = CTColor.accent
    Box(
        Modifier.size(size).drawBehind {
            val radius = this.size.minDimension / 2 * (1f + 0.5f * progress)
            drawCircle(accent.copy(alpha = 0.5f * (1f - progress)), radius = radius, style = Stroke(width = 2.dp.toPx()))
        },
    )
}

/**
 * The call, shrunk to a strip over the app while it goes on. iOS `InCallMiniBar`. Tapping it
 * returns to the call screen.
 */
@Composable
fun CallMiniBar(call: CallUi, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CTColor.accent)
            .clickable(onClick = onOpen)
            .statusBarsPadding()
            .padding(horizontal = CTLayout.edgePad, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Call, contentDescription = null, tint = CTColor.bg, modifier = Modifier.size(CTIcon.row))
        Spacer(Modifier.width(8.dp))
        Text(call.peerName, style = CTFont.ui(12, FontWeight.Bold), color = CTColor.bg, modifier = Modifier.weight(1f), maxLines = 1)
        Text(
            text = if (call.phase == CallUi.Phase.ACTIVE) elapsed(call.activeSinceMs) else stringResource(R.string.call_minibar_connecting),
            style = CTFont.caption,
            color = CTColor.bg,
        )
    }
}

private val AVATAR = 96.dp
