package com.construct.messenger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.construct.messenger.R
import com.construct.messenger.ui.theme.CTColor

/**
 * The mic button, which also offers the camera. **Canon:** iOS `MicModeButton` — a tap records a
 * voice message, as it always has. Held, it opens a two-segment switch — the camera above, the
 * mic under the finger — and the finger, without lifting, slides up to the camera; releasing on a
 * segment starts that recording, releasing away from the switch starts nothing. Upwards, not
 * leftwards as on iOS: the owner's call (2026-10-04) — a thumb on the mic slides up naturally,
 * and the switch does not cover the field.
 *
 * Both choices are on screen at the moment of choosing and nothing is remembered between presses:
 * a tap is always the voice message, so the camera never comes on unasked.
 */
@Composable
fun MicModeButton(size: Dp, onVoice: () -> Unit, onVideoNote: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    var choice by remember { mutableStateOf<MicSwitch.Mode?>(null) }
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val sizePx = with(density) { size.toPx() }
    val segmentPx = with(density) { MicSwitch.SEGMENT.toPx() }
    val marginPx = with(density) { MicSwitch.CANCEL_MARGIN.toPx() }
    val voice by rememberUpdatedState(onVoice)
    val note by rememberUpdatedState(onVideoNote)
    val voiceLabel = stringResource(R.string.voice_record)
    val noteLabel = stringResource(R.string.video_note_record)

    Box(
        modifier = Modifier
            .size(size)
            .semantics {
                contentDescription = voiceLabel
                role = Role.Button
                onClick { voice(); true }
                customActions = listOf(CustomAccessibilityAction(noteLabel) { note(); true })
            }
            .pointerInput(sizePx, segmentPx, marginPx) {
                awaitEachGesture {
                    awaitFirstDown()
                    var released = false
                    val early = withTimeoutOrNull(MicSwitch.PRESS_DELAY_MS) {
                        waitForUpOrCancellation().also { released = true }
                    }
                    if (released) {
                        // Up (or cancelled) before the switch opened: a tap is the voice message.
                        if (early != null) voice()
                        return@awaitEachGesture
                    }
                    open = true
                    choice = MicSwitch.Mode.VOICE
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull() ?: break
                        val next = MicSwitch.mode(change.position.x, change.position.y, sizePx, segmentPx, marginPx)
                        if (next != null && next != choice) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        choice = next
                        change.consume()
                        if (!change.pressed) break
                    }
                    val chosen = choice
                    open = false
                    choice = null
                    when (chosen) {
                        MicSwitch.Mode.VOICE -> voice()
                        MicSwitch.Mode.VIDEO_NOTE -> note()
                        null -> Unit
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Mic,
            contentDescription = null,
            tint = CTColor.textDim,
            modifier = Modifier.size(size).padding(2.dp).alpha(if (open) 0f else 1f),
        )
        if (open) {
            // Grows upwards from the button's bottom edge, over the transcript. A popup, because
            // the composer's capsule clips what its children draw outside it. Not focusable: the
            // press that opened it stays with the button, which reads where the finger goes.
            Popup(
                alignment = Alignment.BottomCenter,
                properties = PopupProperties(focusable = false, clippingEnabled = false),
            ) {
                Column(
                    modifier = Modifier
                        .width(MicSwitch.THICKNESS)
                        .background(CTColor.bgMsg, CircleShape),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Segment(Icons.Filled.Videocam, choice == MicSwitch.Mode.VIDEO_NOTE)
                    Segment(Icons.Filled.Mic, choice == MicSwitch.Mode.VOICE)
                }
            }
        }
    }
}

@Composable
private fun Segment(icon: androidx.compose.ui.graphics.vector.ImageVector, chosen: Boolean) {
    Box(
        modifier = Modifier
            .width(MicSwitch.THICKNESS)
            .height(MicSwitch.SEGMENT)
            .padding(2.dp)
            .background(if (chosen) CTColor.accent else androidx.compose.ui.graphics.Color.Transparent, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = if (chosen) CTColor.bg else CTColor.textDim, modifier = Modifier.size(22.dp))
    }
}

/** **Canon:** iOS `MicModeButton.mode(at:buttonSize:)` and `ChatUIConstants.VideoNote`. Pure. */
object MicSwitch {
    enum class Mode { VOICE, VIDEO_NOTE }

    const val PRESS_DELAY_MS = 350L
    /** Each segment's length along the switch. */
    val SEGMENT = 52.dp
    /** The switch's width across. */
    val THICKNESS = 40.dp

    /** How far past the switch a finger may drift and still choose; beyond it, release cancels. */
    val CANCEL_MARGIN = 44.dp

    /** Which segment is under ([x], [y]) in the button's own coordinates — the switch grows
     * upwards from its bottom edge — or null when the finger has left it. */
    fun mode(x: Float, y: Float, size: Float, segment: Float, margin: Float): Mode? {
        val bottom = size
        val top = size - 2 * segment
        if (y <= top - margin || y >= bottom + margin || x <= -margin || x >= size + margin) return null
        return if (y < bottom - segment) Mode.VIDEO_NOTE else Mode.VOICE
    }
}
