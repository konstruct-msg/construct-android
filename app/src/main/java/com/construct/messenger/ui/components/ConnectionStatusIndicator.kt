package com.construct.messenger.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.construct.messenger.ui.theme.CTColor
import kotlinx.coroutines.delay

/**
 * Connection state for the chat-list header indicator.
 *
 * **Canon:** iOS `ConnectionStatusManager.ConnectionStatus`.
 */
enum class ConnectionStatus {
    CONNECTED,
    CONNECTING,
    DISCONNECTED,
    UNKNOWN,
}

/**
 * The chat-list header's connection dot — no text.
 *
 * **Canon:** iOS `ConnectionStatusIndicator.swift`:
 * - Connecting (and unknown, and a drop before the first successful connect): a dim, pulsing dot —
 *   a cold start never flashes "disconnected".
 * - Connected: a full accent dot with a glow for three seconds, then it fades away; a healthy
 *   connection adds no permanent chrome.
 * - Disconnected after having connected: a steady danger dot.
 * - Paused stream: a faint dot.
 *
 * Identical for a direct and a relayed connection (`decisions/silent-transport-ui`). The version
 * this replaces lit up in the accent only when VEIL was running — telling whoever holds the phone
 * how it connects.
 */
@Composable
fun ConnectionStatusIndicator(
    status: ConnectionStatus,
    modifier: Modifier = Modifier,
    isStreamPaused: Boolean = false,
) {
    var hasConnectedOnce by remember { mutableStateOf(false) }
    if (status == ConnectionStatus.CONNECTED) hasConnectedOnce = true
    val state = when {
        isStreamPaused -> DotState.PAUSED
        status == ConnectionStatus.CONNECTED -> DotState.CONNECTED
        status == ConnectionStatus.DISCONNECTED && hasConnectedOnce -> DotState.DISCONNECTED
        else -> DotState.CONNECTING
    }
    val color = when (state) {
        DotState.CONNECTED -> CTColor.accent
        DotState.CONNECTING -> CTColor.textDim
        DotState.DISCONNECTED -> CTColor.danger.copy(alpha = 0.8f)
        DotState.PAUSED -> CTColor.textDim.copy(alpha = 0.45f)
    }
    val fade = remember { Animatable(1f) }
    LaunchedEffect(state) {
        fade.snapTo(1f)
        if (state == DotState.CONNECTED) {
            delay(3_000)
            fade.animateTo(0f, tween(durationMillis = 800))
        }
    }
    val pulse = if (state == DotState.CONNECTING) {
        val transition = rememberInfiniteTransition(label = "connecting-pulse")
        transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.5f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 1_000), RepeatMode.Reverse),
            label = "pulse",
        ).value
    } else {
        1f
    }
    val scale = if (state == DotState.CONNECTING) 0.6f + 0.4f * ((pulse - 0.5f) / 0.5f) else 1f
    Box(
        modifier = modifier
            .size(8.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = (if (state == DotState.PAUSED) 0.6f else pulse) * fade.value
            }
            .then(
                if (state == DotState.CONNECTED) {
                    Modifier.shadow(4.dp, CircleShape, ambientColor = CTColor.accent, spotColor = CTColor.accent)
                } else {
                    Modifier
                },
            )
            .background(color, CircleShape),
    )
}

private enum class DotState { CONNECTING, CONNECTED, DISCONNECTED, PAUSED }

@Preview(backgroundColor = 0xFF090909, showBackground = true, widthDp = 120)
@Composable
private fun ConnectionStatusIndicatorPreview() {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(12.dp)) {
        ConnectionStatusIndicator(status = ConnectionStatus.CONNECTED)
        ConnectionStatusIndicator(status = ConnectionStatus.CONNECTING)
        ConnectionStatusIndicator(status = ConnectionStatus.DISCONNECTED)
        ConnectionStatusIndicator(status = ConnectionStatus.CONNECTED, isStreamPaused = true)
    }
}
