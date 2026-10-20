package com.construct.messenger.ui.screens.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow

/**
 * Whether the transcript in [listState] follows its newest message ([TranscriptFollow]), kept
 * across a rotation. A tester, 2026-10-20: turning the phone while reading history jumped to the
 * newest message. The list's position survived, but this was a plain `remember`, so it came
 * back as following and the chat was scrolled to its end.
 */
@Composable
internal fun rememberTranscriptFollow(listState: LazyListState, slackPx: Int): MutableState<Boolean> {
    val following = rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            TranscriptFollow.nearBottom(last?.index, last?.let { it.offset + it.size } ?: 0, info.totalItemsCount, info.viewportEndOffset, slackPx) to
                listState.isScrollInProgress
        }.collect { (near, scrolling) -> following.value = TranscriptFollow.next(following.value, near, scrolling) }
    }
    return following
}
