package com.construct.messenger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTSpace
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

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
 * - Connecting (and unknown, and a drop before the first successful connect): a dim, blinking dot —
 *   a cold start never flashes "disconnected".
 * - Connected: a steady green dot ([CTColor.online]) with a soft glow. It stays — the dot is how
 *   the person sees they are online; a dot that faded out read as "something went" (iOS PR #84).
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
        DotState.CONNECTED -> CTColor.online
        DotState.CONNECTING -> CTColor.textDim
        DotState.DISCONNECTED -> CTColor.danger.copy(alpha = 0.8f)
        DotState.PAUSED -> CTColor.textDim.copy(alpha = 0.45f)
    }
    val dimmed = if (state == DotState.CONNECTING) {
        remember { connectingBlink() }.collectAsStateWithLifecycle(initialValue = false)
    } else {
        null
    }
    Box(
        modifier = modifier
            .size(8.dp)
            .graphicsLayer {
                // Read here, in the draw phase: a blink redraws the dot and recomposes nothing.
                alpha = when {
                    state == DotState.PAUSED -> 0.6f
                    dimmed?.value == true -> BLINK_DIM_ALPHA
                    else -> 1f
                }
            }
            .then(
                if (state == DotState.CONNECTED) {
                    Modifier.shadow(4.dp, CircleShape, ambientColor = CTColor.online, spotColor = CTColor.online)
                } else {
                    Modifier
                },
            )
            .background(color, CircleShape),
    )
}

private const val BLINK_DIM_ALPHA = 0.5f
internal const val BLINK_HALF_MS = 1_000L

/**
 * The connecting dot's blink: one change a second, one frame a change.
 *
 * It was an infinite tween: 60 frames a second of the whole window for an 8 dp dot, half a core of
 * a Redmi Note 8 Pro for as long as there was no connection (2026-11-25).
 */
internal fun connectingBlink(): Flow<Boolean> = flow {
    var dim = false
    while (true) {
        emit(dim)
        delay(BLINK_HALF_MS)
        dim = !dim
    }
}

private enum class DotState { CONNECTING, CONNECTED, DISCONNECTED, PAUSED }

@Preview(backgroundColor = 0xFF090909, showBackground = true, widthDp = 120)
@Composable
private fun ConnectionStatusIndicatorPreview() {
    Row(horizontalArrangement = Arrangement.spacedBy(CTSpace.m), modifier = Modifier.padding(CTSpace.m)) {
        ConnectionStatusIndicator(status = ConnectionStatus.CONNECTED)
        ConnectionStatusIndicator(status = ConnectionStatus.CONNECTING)
        ConnectionStatusIndicator(status = ConnectionStatus.DISCONNECTED)
        ConnectionStatusIndicator(status = ConnectionStatus.CONNECTED, isStreamPaused = true)
    }
}
