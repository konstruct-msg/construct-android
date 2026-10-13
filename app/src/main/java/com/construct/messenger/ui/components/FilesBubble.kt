@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.construct.messenger.ui.components

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.ArrowCircleDown
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.construct.messenger.R
import com.construct.messenger.data.model.MediaItem
import com.construct.messenger.data.model.MessageMedia
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTFont
import com.construct.messenger.ui.theme.CTIcon
import com.construct.messenger.ui.theme.CTSpace
import com.construct.messenger.util.MediaWire

/**
 * **Canon:** iOS `FileAttachmentBubbleView` — in the message colours, radius 10 with a hairline:
 * one row per file — an icon by extension, the name on one line, the size — and a download mark
 * that becomes a spinner while the file is fetched; the caption below. Tap fetches and opens it.
 *
 * A music file (`audio/`) is a row like any file, with a play button in place of its icon: it
 * plays in place, in the voice notes' player, with progress and time under the name; the name
 * still opens it in the system's viewer (iOS, 2026-10-13).
 */
@Composable
fun FilesBubble(
    album: MessageMedia.Album,
    caption: String,
    outgoing: Boolean,
    maxWidth: Dp,
    loading: Set<String>,
    unavailable: Set<String>,
    onOpen: (MediaItem) -> Unit,
    /** The message menu. The rows take the touch, so a long press must be theirs to pass on. */
    onLongPress: () -> Unit = {},
    tracks: TrackPlayback = TrackPlayback(),
    onToggleTrack: (MediaItem) -> Unit = {},
) {
    val shape = RoundedCornerShape(10.dp)
    val tint = if (outgoing) CTColor.outMsgText else CTColor.accent
    val text = if (outgoing) CTColor.outMsgText else CTColor.text
    val dim = if (outgoing) CTColor.outMsgText.copy(alpha = 0.7f) else CTColor.textDim
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .widthIn(max = maxWidth)
            .width(IntrinsicSize.Max)
            .background(if (outgoing) CTColor.outMsgBg else CTColor.bgMsg, shape)
            .border(0.5.dp, CTColor.noise, shape)
            .padding(CTSpace.m),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        album.items.forEach { item ->
            val staged = item.mediaId.startsWith(MediaWire.LOCAL_PREFIX)
            Row(
                modifier = Modifier.fillMaxWidth().combinedClickable(
                    onClick = { if (!staged && item.mediaId !in unavailable) onOpen(item) },
                    onLongClick = onLongPress,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val track = item.takeIf { it.isAudio && !staged }
                val playing = track?.let { tracks.playing?.takeIf { p -> p.mediaId == it.mediaId } }
                if (track != null) {
                    TrackButton(
                        playing = playing != null && !playing.paused,
                        loading = track.mediaId in tracks.loading,
                        unavailable = track.mediaId in tracks.unavailable,
                        tint = tint,
                        onClick = { onToggleTrack(track) },
                    )
                } else {
                    Icon(iconFor(item.filename), null, tint = tint, modifier = Modifier.width(32.dp).size(CTIcon.navLg))
                }
                Spacer(Modifier.width(CTSpace.s))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = item.filename ?: stringResource(R.string.file_attachment),
                        style = CTFont.message(13).copy(fontWeight = FontWeight.Medium),
                        color = text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (playing != null) {
                        LinearProgressIndicator(
                            progress = { playing.progress },
                            color = tint,
                            trackColor = dim.copy(alpha = 0.3f),
                            modifier = Modifier.fillMaxWidth().padding(vertical = CTSpace.xs),
                        )
                        Text(
                            text = "${formatVoiceDuration((playing.progress * playing.durationMs).toLong())} / ${formatVoiceDuration(playing.durationMs)}",
                            style = CTFont.caption,
                            color = dim,
                        )
                    } else {
                        Text(
                            text = if (item.mediaId in unavailable || item.mediaId in tracks.unavailable) stringResource(R.string.media_unavailable)
                            else Formatter.formatShortFileSize(context, item.sizeBytes),
                            style = CTFont.caption,
                            color = dim,
                        )
                    }
                }
                Spacer(Modifier.width(CTSpace.m))
                Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                    when {
                        staged || item.mediaId in loading ->
                            CircularProgressIndicator(color = tint, strokeWidth = 2.dp, modifier = Modifier.size(CTIcon.row))
                        item.mediaId in unavailable -> Icon(Icons.Filled.Warning, null, tint = CTColor.danger)
                        else -> Icon(Icons.Filled.ArrowCircleDown, stringResource(R.string.open_file), tint = tint)
                    }
                }
            }
        }
        if (caption.isNotBlank()) {
            Text(caption, style = CTFont.message(12), color = text, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** What the player says about the tracks in a bubble: the one playing, and those being fetched or gone. */
data class TrackPlayback(
    val playing: Playing? = null,
    val loading: Set<String> = emptySet(),
    val unavailable: Set<String> = emptySet(),
) {
    data class Playing(val mediaId: String, val progress: Float, val durationMs: Long, val paused: Boolean)
}

@Composable
private fun TrackButton(playing: Boolean, loading: Boolean, unavailable: Boolean, tint: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Box(Modifier.width(32.dp).size(CTIcon.navLg), contentAlignment = Alignment.Center) {
        when {
            loading -> CircularProgressIndicator(color = tint, strokeWidth = 2.dp, modifier = Modifier.size(CTIcon.row))
            unavailable -> Icon(Icons.Filled.Warning, null, tint = CTColor.danger)
            else -> IconButton(onClick = onClick) {
                Icon(
                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    stringResource(if (playing) R.string.track_pause else R.string.track_play),
                    tint = tint,
                )
            }
        }
    }
}

/** iOS `symbolName(for:)`, in Material icons. */
private fun iconFor(name: String?): ImageVector = when (name?.substringAfterLast('.', "")?.lowercase()) {
    "pdf" -> Icons.Filled.PictureAsPdf
    "md", "markdown", "txt", "docx", "doc" -> Icons.Filled.Description
    "zip", "gz", "tar", "7z" -> Icons.Filled.FolderZip
    // iOS `MessageSizeLimits.supportedAudioTypes`.
    "mp3", "m4a", "wav", "aac", "flac", "ogg", "opus", "aiff", "aif", "caf", "m4b" -> Icons.Filled.AudioFile
    "mp4", "mov", "m4v" -> Icons.Filled.Movie
    "xlsx", "xls", "csv" -> Icons.Filled.TableChart
    else -> Icons.AutoMirrored.Filled.InsertDriveFile
}

@androidx.compose.ui.tooling.preview.Preview(widthDp = 360)
@Composable
private fun FilesBubbleTrackPreview() {
    fun item(id: String, mime: String, name: String, size: Long) =
        MediaItem(id, ByteArray(32), ByteArray(32), size, mime, filename = name)
    Column(Modifier.padding(CTSpace.s), verticalArrangement = Arrangement.spacedBy(CTSpace.s)) {
        FilesBubble(
            album = MessageMedia.Album(listOf(item("t1", "audio/mpeg", "Night Drive.mp3", 4_200_000), item("t2", "audio/flac", "Intro.flac", 18_000_000))),
            caption = "",
            outgoing = false,
            maxWidth = 300.dp,
            loading = emptySet(),
            unavailable = emptySet(),
            onOpen = {},
            tracks = TrackPlayback(playing = TrackPlayback.Playing("t1", 0.4f, 215_000, paused = false)),
        )
        FilesBubble(
            album = MessageMedia.Album(listOf(item("d1", "application/pdf", "report.pdf", 700_000))),
            caption = "the numbers",
            outgoing = true,
            maxWidth = 300.dp,
            loading = emptySet(),
            unavailable = emptySet(),
            onOpen = {},
        )
    }
}
