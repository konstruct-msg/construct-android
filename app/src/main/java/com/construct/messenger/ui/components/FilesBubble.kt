package com.construct.messenger.ui.components

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import com.construct.messenger.ui.theme.ctMessage
import com.construct.messenger.ui.theme.ctRegular
import com.construct.messenger.util.MediaWire

/**
 * **Canon:** iOS `FileAttachmentBubbleView` — in the message colours, radius 10 with a hairline:
 * one row per file — an icon by extension, the name on one line, the size — and a download mark
 * that becomes a spinner while the file is fetched; the caption below. Tap fetches and opens it.
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
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        album.items.forEach { item ->
            val staged = item.mediaId.startsWith(MediaWire.LOCAL_PREFIX)
            Row(
                modifier = Modifier.fillMaxWidth().clickable(enabled = !staged && item.mediaId !in unavailable) { onOpen(item) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(iconFor(item.filename), null, tint = tint, modifier = Modifier.width(32.dp).size(22.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = item.filename ?: stringResource(R.string.file_attachment),
                        style = ctMessage(13).copy(fontWeight = FontWeight.Medium),
                        color = text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (item.mediaId in unavailable) stringResource(R.string.media_unavailable)
                        else Formatter.formatShortFileSize(context, item.sizeBytes),
                        style = ctRegular(11),
                        color = dim,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                    when {
                        staged || item.mediaId in loading ->
                            CircularProgressIndicator(color = tint, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                        item.mediaId in unavailable -> Icon(Icons.Filled.Warning, null, tint = CTColor.danger)
                        else -> Icon(Icons.Filled.ArrowCircleDown, stringResource(R.string.open_file), tint = tint)
                    }
                }
            }
        }
        if (caption.isNotBlank()) {
            Text(caption, style = ctMessage(12), color = text, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** iOS `symbolName(for:)`, in Material icons. */
private fun iconFor(name: String?): ImageVector = when (name?.substringAfterLast('.', "")?.lowercase()) {
    "pdf" -> Icons.Filled.PictureAsPdf
    "md", "markdown", "txt", "docx", "doc" -> Icons.Filled.Description
    "zip", "gz", "tar", "7z" -> Icons.Filled.FolderZip
    "mp3", "aac", "m4a", "wav" -> Icons.Filled.AudioFile
    "mp4", "mov", "m4v" -> Icons.Filled.Movie
    "xlsx", "xls", "csv" -> Icons.Filled.TableChart
    else -> Icons.AutoMirrored.Filled.InsertDriveFile
}
