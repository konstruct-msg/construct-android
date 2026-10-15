package com.construct.messenger.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.construct.messenger.R
import com.construct.messenger.media.PlaybackSource
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTFont
import com.construct.messenger.ui.theme.CTLayout
import com.construct.messenger.ui.theme.CTSpace
import com.construct.messenger.ui.theme.CornerRadius
import com.construct.messenger.ui.theme.HairlineBorder
import com.construct.messenger.viewmodel.NowPlayingUi

/**
 * The track still playing after its chat was left: a card at the top of every other screen.
 * Pause, stop, and a tap that goes back to the message (TODO 151). **Canon:** iOS
 * `NowPlayingBanner` — the shape is the room banner's from the room concept, so the room can take
 * this place later.
 */
@Composable
fun NowPlayingBanner(
    state: NowPlayingUi,
    onToggle: () -> Unit,
    onStop: () -> Unit,
    onOpen: (PlaybackSource) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(CornerRadius.control)
    Surface(
        color = CTColor.bgMsg,
        shape = shape,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = CTLayout.edgePad, vertical = CTSpace.xs)
            .border(HairlineBorder, CTColor.accentDim, shape),
    ) {
        Column(Modifier.padding(bottom = CTSpace.s)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onToggle) {
                    Icon(
                        if (state.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                        contentDescription = stringResource(if (state.paused) R.string.track_play else R.string.track_pause),
                        tint = CTColor.accent,
                    )
                }
                val hint = stringResource(R.string.now_playing_open_hint)
                Column(
                    Modifier
                        .weight(1f)
                        .clickable(onClickLabel = hint) { onOpen(state.source) },
                ) {
                    Text(
                        text = state.source.fileName ?: stringResource(R.string.voice_message),
                        style = CTFont.ui(13, FontWeight.Medium),
                        color = CTColor.text,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                    Text(
                        text = "${state.chatName} · ${formatVoiceDuration(state.elapsedMs)}",
                        style = CTFont.caption,
                        color = CTColor.textDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onStop) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.audio_stop), tint = CTColor.textDim)
                }
            }
            LinearProgressIndicator(
                progress = { state.progress },
                color = CTColor.accent,
                trackColor = CTColor.noise,
                modifier = Modifier.fillMaxWidth().padding(horizontal = CTSpace.m),
            )
        }
    }
}

@Preview(backgroundColor = 0xFF090909, showBackground = true, widthDp = 360)
@Composable
private fun NowPlayingBannerPreview() {
    NowPlayingBanner(
        state = NowPlayingUi(PlaybackSource("peer", "m1", "demo-bridge-v3.flac"), "Kim", paused = false, progress = 0.4f, elapsedMs = 83_000),
        onToggle = {},
        onStop = {},
        onOpen = {},
    )
}
