@file:OptIn(ExperimentalFoundationApi::class)

package com.construct.messenger.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowCircleUp
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.construct.messenger.R
import com.construct.messenger.data.model.MessageMedia
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTFont
import com.construct.messenger.ui.theme.CTIcon
import com.construct.messenger.ui.theme.CTSpace
import kotlin.math.max
import kotlin.math.min

/** How the waveform is drawn. **Canon:** iOS `VoiceWaveformView.Style`. */
sealed interface WaveStyle {
    /** In a bubble: bars up to the played fraction bright, the rest dim. */
    data class Playback(val progress: Float, val outgoing: Boolean) : WaveStyle

    /** The recorded note, before sending: all accent. */
    data object StaticAccent : WaveStyle

    /** While recording: the newest samples at the right edge. */
    data object LiveInput : WaveStyle
}

/**
 * **Canon:** iOS `VoiceWaveformView` — at most 64 capsule bars 2 apart, as many as fit at their
 * minimum width; the samples averaged into that many (padded when fewer).
 */
@Composable
fun VoiceWaveform(samples: List<Float>, style: WaveStyle, modifier: Modifier = Modifier) {
    val accent = CTColor.accent
    val outText = CTColor.outMsgText
    val dim = CTColor.textDim
    Canvas(modifier) {
        val gap = 2.dp.toPx()
        val minBar = (if (style is WaveStyle.Playback) 1.dp else 1.5.dp).toPx()
        val count = max(1, min(PREFERRED_BARS, ((size.width + gap) / (minBar + gap)).toInt()))
        val barW = max(minBar, (size.width - gap * (count - 1)) / count)
        val values: List<Float> = when (style) {
            is WaveStyle.LiveInput -> List(count) { i -> samples.getOrNull(samples.size - count + i) ?: -1f }
            else -> downsample(samples, count)
        }
        for (i in 0 until count) {
            val v = values[i]
            val h = when (style) {
                is WaveStyle.Playback -> max(2.dp.toPx(), v * size.height)
                WaveStyle.StaticAccent -> max(5.dp.toPx(), v * size.height * 0.85f)
                WaveStyle.LiveInput -> if (v < 0) 4.dp.toPx() else max(4.dp.toPx(), v * size.height * 0.9f)
            }
            val color = when (style) {
                is WaveStyle.Playback -> {
                    val played = style.progress > 0 && i.toFloat() / max(count - 1, 1) <= style.progress
                    when {
                        played && style.outgoing -> outText.copy(alpha = 0.95f)
                        played -> accent
                        style.outgoing -> outText.copy(alpha = 0.35f)
                        else -> dim.copy(alpha = 0.45f)
                    }
                }
                WaveStyle.StaticAccent -> accent.copy(alpha = 0.7f)
                WaveStyle.LiveInput -> accent.copy(alpha = if (v < 0) 0.25f else 1f)
            }
            drawRoundRect(
                color = color,
                topLeft = Offset(i * (barW + gap), (size.height - h) / 2),
                size = Size(barW, h),
                cornerRadius = CornerRadius(barW / 2, barW / 2),
            )
        }
    }
}

/** iOS `downsample`: averaged buckets; fewer samples than bars are padded low. */
internal fun downsample(samples: List<Float>, count: Int): List<Float> {
    if (samples.isEmpty()) return List(count) { 0.3f }
    if (samples.size < count) return samples + List(count - samples.size) { 0.1f }
    val step = samples.size.toFloat() / count
    return List(count) { i ->
        val start = (i * step).toInt()
        val end = min(((i + 1) * step).toInt(), samples.size)
        if (start >= end) 0.1f else samples.subList(start, end).average().toFloat()
    }
}

/** What a voice bubble shows besides the note itself. */
data class VoicePlayback(
    /** Null when this note is not the one playing. */
    val progress: Float? = null,
    val paused: Boolean = false,
    val playingDurationMs: Long = 0,
    val loading: Boolean = false,
    val unavailable: Boolean = false,
    /** Ours, still uploading. */
    val uploading: Boolean = false,
)

/**
 * **Canon:** iOS `VoiceMessageBubbleView` — a bubble in the message colours, radius 10 with a
 * hairline: play/pause (a spinner while fetching), the waveform 28 high, and the length, counting
 * down while it plays. Transcription is not here.
 */
@Composable
fun VoiceBubble(
    voice: MessageMedia.Voice,
    outgoing: Boolean,
    playback: VoicePlayback,
    maxWidth: Dp,
    onToggle: () -> Unit,
    onLongPress: () -> Unit,
    onDoubleTap: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(10.dp)
    val tint = if (outgoing) CTColor.outMsgText else CTColor.accent
    val durationMs = voice.audio.durationMs ?: 0L
    val shownMs = if (playback.progress != null && playback.playingDurationMs > 0) {
        (playback.playingDurationMs * (1 - playback.progress)).toLong()
    } else {
        durationMs
    }
    Row(
        modifier = Modifier
            .widthIn(max = maxWidth)
            .fillMaxWidth()
            .alpha(if (playback.uploading) 0.7f else 1f)
            .background(if (outgoing) CTColor.outMsgBg else CTColor.bgMsg, shape)
            .border(0.5.dp, CTColor.noise, shape)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
                onLongClick = onLongPress,
                onDoubleClick = onDoubleTap,
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(38.dp).height(28.dp), contentAlignment = Alignment.Center) {
            when {
                playback.uploading || playback.loading ->
                    CircularProgressIndicator(color = tint, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                playback.unavailable ->
                    Icon(Icons.Filled.Warning, stringResource(R.string.media_unavailable), tint = CTColor.danger, modifier = Modifier.size(CTIcon.row))
                else -> Icon(
                    imageVector = if (playback.progress != null && !playback.paused) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(R.string.voice_message),
                    tint = tint,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(onClick = onToggle)
                        .padding(CTSpace.xs)
                        .size(CTIcon.control),
                )
            }
        }
        VoiceWaveform(
            samples = voice.waveform.map { it / 255f },
            style = WaveStyle.Playback(playback.progress ?: 0f, outgoing),
            modifier = Modifier
                .weight(1f)
                .height(28.dp)
                .padding(horizontal = 6.dp)
                .alpha(if (playback.uploading) 0.4f else 1f),
        )
        Text(
            text = formatVoiceDuration(shownMs),
            style = CTFont.caption,
            color = if (outgoing) CTColor.outMsgText.copy(alpha = 0.85f) else CTColor.textDim,
            textAlign = TextAlign.End,
            modifier = Modifier.width(34.dp),
        )
    }
}

/**
 * **Canon:** iOS `VoiceRecordingBar` / `VoicePreviewBar` — in place of the composer row, the same
 * 44-high pill in the outgoing colour with an accent hairline: cancel (or discard), the waveform,
 * the time, stop (or send).
 */
@Composable
fun VoiceComposerBar(
    recording: Boolean,
    durationMs: Long,
    waveform: List<Float>,
    onLeading: () -> Unit,
    onTrailing: () -> Unit,
) {
    val shape = RoundedCornerShape(percent = 50)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = CTSpace.l, vertical = CTSpace.xs)
            .height(44.dp)
            .background(CTColor.outMsgBg, shape)
            .border(1.dp, CTColor.accent.copy(alpha = 0.25f), shape)
            .padding(horizontal = CTSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (recording) Icons.Filled.Cancel else Icons.Filled.Delete,
            contentDescription = stringResource(if (recording) R.string.action_cancel else R.string.delete),
            tint = CTColor.danger,
            // 24 drawn, 40 to press: a small target inside a pill is easy to miss.
            modifier = Modifier.clip(CircleShape).clickable(onClick = onLeading).padding(CTSpace.s).size(CTIcon.control),
        )
        VoiceWaveform(
            samples = waveform,
            style = if (recording) WaveStyle.LiveInput else WaveStyle.StaticAccent,
            modifier = Modifier.weight(1f).height(28.dp).padding(horizontal = CTSpace.m),
        )
        Text(
            text = formatVoiceDuration(durationMs),
            style = CTFont.ui(14),
            color = CTColor.textDim,
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(min = 42.dp),
        )
        Icon(
            imageVector = if (recording) Icons.Filled.StopCircle else Icons.Filled.ArrowCircleUp,
            contentDescription = stringResource(if (recording) R.string.voice_stop else R.string.chat_send),
            tint = CTColor.accent,
            modifier = Modifier.clip(CircleShape).clickable(onClick = onTrailing).padding(6.dp).size(if (recording) 24.dp else 28.dp),
        )
    }
}

/** iOS `VoiceUIDurationFormatter`: m:ss. */
fun formatVoiceDuration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

private const val PREFERRED_BARS = 64
