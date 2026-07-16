package com.construct.messenger.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.ctRegular

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
 * Compact connection status badge for the chat list header.
 *
 * **Canon:** iOS `ConnectionStatusIndicator.swift`.
 * - Connected: accent when VEIL is active, otherwise dim.
 * - Connecting: dim + pulsing opacity.
 * - Disconnected: danger.
 * - Paused stream: extra-dim.
 */
@Composable
fun ConnectionStatusIndicator(
    status: ConnectionStatus,
    modifier: Modifier = Modifier,
    isStreamPaused: Boolean = false,
    isVeilRunning: Boolean = false,
) {
    val label = statusLabel(status)
    val baseColor = when {
        isStreamPaused -> CTColor.textDim
        status == ConnectionStatus.CONNECTED && isVeilRunning -> CTColor.accent
        status == ConnectionStatus.CONNECTED -> CTColor.textDim
        status == ConnectionStatus.DISCONNECTED -> CTColor.danger
        else -> CTColor.textDim
    }
    val alpha = when {
        isStreamPaused -> 0.45f
        status == ConnectionStatus.CONNECTING -> {
            val infiniteTransition = rememberInfiniteTransition(label = "connecting-pulse")
            val pulse by infiniteTransition.animateFloat(
                initialValue = 0.55f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 1100),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "pulse",
            )
            pulse
        }

        else -> 1f
    }

    Text(
        text = label,
        style = ctRegular(11),
        color = baseColor,
        modifier = modifier.alpha(alpha),
    )
}

private fun statusLabel(status: ConnectionStatus): String = when (status) {
    ConnectionStatus.CONNECTED -> "connected"
    ConnectionStatus.CONNECTING -> "connecting..."
    ConnectionStatus.DISCONNECTED -> "disconnected"
    ConnectionStatus.UNKNOWN -> "unknown"
}

@Preview(backgroundColor = 0xFF090909, showBackground = true, widthDp = 200)
@Composable
private fun ConnectionStatusIndicatorPreview() {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ConnectionStatusIndicator(status = ConnectionStatus.CONNECTED)
        ConnectionStatusIndicator(
            status = ConnectionStatus.CONNECTED,
            isVeilRunning = true,
        )
        ConnectionStatusIndicator(status = ConnectionStatus.CONNECTING)
        ConnectionStatusIndicator(status = ConnectionStatus.DISCONNECTED)
        ConnectionStatusIndicator(
            status = ConnectionStatus.CONNECTED,
            isStreamPaused = true,
        )
    }
}
