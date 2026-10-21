package com.construct.messenger.ui.screens.chat

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A tester, 2026-10-21: a new conversation's first messages stuck to the top of the screen.
 * Mutation: [TranscriptArrangement] = `Arrangement.Top` — the last row ends far above the bottom.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = Application::class)
class TranscriptArrangementTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var list: LazyListState

    @Test
    fun aShortChatSitsOnTheBottomInReadingOrder() {
        compose.setContent {
            list = rememberLazyListState()
            LazyColumn(Modifier.fillMaxSize(), state = list, verticalArrangement = TranscriptArrangement) {
                items(listOf(0, 1), key = { it }) { Box(Modifier.fillMaxWidth().height(60.dp)) }
            }
        }
        compose.waitForIdle()

        val info = list.layoutInfo
        val (first, last) = info.visibleItemsInfo
        assertEquals("the newest ends on the bottom edge", info.viewportEndOffset, last.offset + last.size)
        assertTrue("the older one above it", first.index == 0 && first.offset < last.offset)
        assertTrue("empty space above, not below", first.offset > 0)
    }
}
