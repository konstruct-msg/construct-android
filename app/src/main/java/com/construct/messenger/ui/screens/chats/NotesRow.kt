package com.construct.messenger.ui.screens.chats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.construct.messenger.R
import com.construct.messenger.data.local.Note
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTFont
import com.construct.messenger.ui.theme.CTIcon
import com.construct.messenger.ui.theme.CTLayout
import com.construct.messenger.ui.theme.CTSpace
import com.construct.messenger.ui.theme.forScriptOf

/**
 * The Notes row of the chat list ([ChatsListEntry.Notes]), laid out as [ChatRow]: a plain mark
 * where a chat has an avatar, "Notes", the newest note's first line and its time.
 * **Canon:** iOS `NotesRowView`.
 */
@Composable
fun NotesRow(newest: Note?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val preview = newest?.text?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() }
        ?: if (newest == null) stringResource(R.string.notes_stay_here) else stringResource(R.string.notes_note)
    Column(modifier = modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = CTSpace.l, vertical = CTSpace.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Plain, not a person's avatar.
            Box(
                modifier = Modifier.size(AvatarSize).clip(CircleShape).background(CTColor.accentDim),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.Notes,
                    contentDescription = null,
                    tint = CTColor.text,
                    modifier = Modifier.size(CTIcon.nav),
                )
            }
            Spacer(Modifier.width(CTLayout.chromeGap))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CTSpace.xs)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.notes_title),
                        style = CTFont.bodyEmphasis,
                        color = CTColor.text,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    newest?.let {
                        Spacer(Modifier.width(CTSpace.xs))
                        Text(
                            text = rowTimestamp(context, it.createdAtMs),
                            style = CTFont.caption,
                            color = CTColor.textDim,
                            maxLines = 1,
                        )
                    }
                }
                Text(
                    text = preview,
                    style = CTFont.secondary.forScriptOf(preview),
                    color = CTColor.textDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        HorizontalDivider(Modifier.padding(start = CTSpace.l + AvatarSize + CTLayout.chromeGap, end = CTSpace.l))
    }
}
