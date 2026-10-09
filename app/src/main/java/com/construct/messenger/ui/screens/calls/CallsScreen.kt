package com.construct.messenger.ui.screens.calls

import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.construct.messenger.R
import com.construct.messenger.data.model.CallHistoryEntry
import com.construct.messenger.ui.components.CTAvatar
import com.construct.messenger.ui.components.CTConfirmDialog
import com.construct.messenger.ui.components.CTMatrixBackground
import com.construct.messenger.ui.components.rememberAvatar
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTFont
import com.construct.messenger.ui.theme.CTIcon
import com.construct.messenger.ui.theme.CTLayout
import com.construct.messenger.ui.theme.CTSpace
import com.construct.messenger.viewmodel.CallHistoryFilter
import com.construct.messenger.viewmodel.CallHistorySection
import com.construct.messenger.viewmodel.CallsViewModel

/**
 * The Calls tab: recent calls on this device, today / yesterday / this week / older.
 *
 * **Canon:** iOS `CallHistoryView`. A tap calls back, as there; iOS's swipe (delete, call back) is
 * a long press here, as on the chats list.
 */
@Composable
fun CallsScreen(viewModel: CallsViewModel = hiltViewModel()) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    if (confirmClear) {
        CTConfirmDialog(
            title = stringResource(R.string.calls_clear_confirm),
            message = "",
            confirmLabel = stringResource(R.string.calls_clear),
            dismissLabel = stringResource(R.string.action_cancel),
            isDestructive = true,
            onConfirm = { confirmClear = false; viewModel.clear() },
            onDismiss = { confirmClear = false },
        )
    }

    Box(modifier = Modifier.fillMaxSize().background(CTColor.bg)) {
        CTMatrixBackground(modifier = Modifier.fillMaxSize())
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(CTLayout.navBarHeight)
                    .padding(horizontal = CTLayout.edgePad),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.calls_recents).uppercase(),
                    style = CTFont.ui(17, FontWeight.SemiBold),
                    color = CTColor.text,
                    modifier = Modifier.weight(1f),
                )
                if (ui.hasAny) {
                    TextButton(onClick = { confirmClear = true }) {
                        Text(stringResource(R.string.calls_clear), style = CTFont.bodyEmphasis, color = CTColor.danger)
                    }
                }
            }
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.padding(start = CTLayout.edgePad, bottom = CTLayout.chromeGap),
            ) {
                CallHistoryFilter.entries.forEachIndexed { index, filter ->
                    SegmentedButton(
                        selected = filter == ui.filter,
                        onClick = { viewModel.select(filter) },
                        shape = SegmentedButtonDefaults.itemShape(index, CallHistoryFilter.entries.size),
                    ) {
                        Text(
                            stringResource(
                                when (filter) {
                                    CallHistoryFilter.ALL -> R.string.calls_filter_all
                                    CallHistoryFilter.MISSED -> R.string.calls_filter_missed
                                },
                            ),
                        )
                    }
                }
            }
            if (ui.sections.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(
                            if (ui.filter == CallHistoryFilter.MISSED) R.string.calls_empty_missed else R.string.calls_empty,
                        ),
                        style = CTFont.body,
                        color = CTColor.textDim,
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    ui.sections.forEach { section ->
                        item(key = "section-${section.kind}") { SectionHeader(section.kind) }
                        items(section.entries, key = { it.id }) { entry ->
                            CallHistoryRow(entry = entry, onDelete = { viewModel.delete(entry.id) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(kind: CallHistorySection.Kind) {
    val title = stringResource(
        when (kind) {
            CallHistorySection.Kind.TODAY -> R.string.calls_section_today
            CallHistorySection.Kind.YESTERDAY -> R.string.calls_section_yesterday
            CallHistorySection.Kind.EARLIER -> R.string.calls_section_earlier
            CallHistorySection.Kind.OLDER -> R.string.calls_section_older
        },
    )
    Text(
        text = title.uppercase(),
        style = CTFont.badge,
        color = CTColor.accent,
        modifier = Modifier
            .fillMaxWidth()
            .background(CTColor.bg.copy(alpha = 0.96f))
            .padding(horizontal = CTLayout.edgePad, vertical = CTSpace.s),
    )
}

private val CallAvatarSize = 40.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CallHistoryRow(entry: CallHistoryEntry, onDelete: () -> Unit) {
    val callBack = rememberCallAction(entry.peerUserId)
    var menu by remember { mutableStateOf(false) }
    Box {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = { callBack?.invoke() }, onLongClick = { menu = true }),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = CTLayout.sectionGap, vertical = CTSpace.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (entry.incoming) Icons.AutoMirrored.Filled.CallReceived else Icons.AutoMirrored.Filled.CallMade,
                    contentDescription = stringResource(if (entry.incoming) R.string.call_incoming else R.string.call_outgoing),
                    tint = directionColor(entry),
                    modifier = Modifier.size(CTIcon.row),
                )
                Spacer(Modifier.width(CTSpace.m))
                CTAvatar(
                    userId = entry.peerUserId,
                    displayName = entry.peerName,
                    image = rememberAvatar(entry.peerAvatar),
                    size = CallAvatarSize,
                )
                Spacer(Modifier.width(CTSpace.m))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = entry.peerName,
                        style = CTFont.ui(15, FontWeight.Bold),
                        color = if (entry.status == CallHistoryEntry.Status.MISSED) CTColor.danger else CTColor.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(statusLabel(entry), style = CTFont.secondary, color = CTColor.textDim)
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = DateUtils.getRelativeTimeSpanString(
                            entry.startedAtMs,
                            System.currentTimeMillis(),
                            DateUtils.MINUTE_IN_MILLIS,
                            DateUtils.FORMAT_ABBREV_RELATIVE,
                        ).toString(),
                        style = CTFont.secondary,
                        color = CTColor.textDim,
                    )
                    formattedDuration(entry.durationSeconds)?.let {
                        Text(it, style = CTFont.micro, color = CTColor.textDim)
                    }
                }
                Spacer(Modifier.width(CTLayout.inlinePad))
                Icon(
                    imageVector = Icons.Default.Call,
                    contentDescription = stringResource(R.string.call_call_back),
                    tint = if (callBack != null) CTColor.accent else CTColor.textDim,
                    modifier = Modifier.size(CTIcon.row),
                )
            }
            // Material's inset divider, from the text column.
            HorizontalDivider(
                Modifier.padding(start = CTLayout.sectionGap + CTIcon.row + CTSpace.m + CallAvatarSize + CTSpace.m),
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, modifier = Modifier.background(CTColor.outMsgBg)) {
            if (callBack != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.call_call_back), style = CTFont.ui(14), color = CTColor.accent) },
                    onClick = { menu = false; callBack() },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.delete), style = CTFont.ui(14), color = CTColor.danger) },
                onClick = { menu = false; onDelete() },
            )
        }
    }
}

@Composable
private fun statusLabel(entry: CallHistoryEntry): String = stringResource(
    when (entry.status) {
        CallHistoryEntry.Status.COMPLETED -> if (entry.incoming) R.string.call_incoming else R.string.call_outgoing
        CallHistoryEntry.Status.MISSED -> R.string.call_missed
        CallHistoryEntry.Status.DECLINED -> R.string.call_declined
        CallHistoryEntry.Status.FAILED -> R.string.call_failed
    },
)

/** iOS `directionColor`. */
private fun directionColor(entry: CallHistoryEntry): Color = when (entry.status) {
    CallHistoryEntry.Status.MISSED, CallHistoryEntry.Status.DECLINED -> CTColor.danger
    CallHistoryEntry.Status.COMPLETED -> if (entry.incoming) CTColor.accent else CTColor.textDim
    CallHistoryEntry.Status.FAILED -> CTColor.warning
}

/** iOS `formattedDuration`: "1:23", nothing for a call nobody answered. */
internal fun formattedDuration(seconds: Int): String? =
    if (seconds <= 0) null else "%d:%02d".format(seconds / 60, seconds % 60)
