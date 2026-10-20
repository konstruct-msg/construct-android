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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A tester, 2026-10-20: turning the phone while reading history jumped to the newest message.
 * A rotation recreates the activity. The list's position was restored, but the follow mode came
 * back on, and the chat's arrival scroll took it to the end. Mutation: `remember` for
 * `rememberSaveable` in [rememberTranscriptFollow] — the list ends at the last row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = Application::class)
class TranscriptFollowRestoreTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val rows = (0 until 100).toList()
    private lateinit var list: LazyListState
    private var following = true

    @Test
    fun readingHistorySurvivesARotation() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            val listState = rememberLazyListState()
            list = listState
            val follow by rememberTranscriptFollow(listState, slackPx = 10)
            following = follow
            // ChatScreen's arrival scroll: to the end while following.
            LaunchedEffect(rows.size) { if (follow) listState.scrollToItem(rows.lastIndex) }
            Box(Modifier.fillMaxSize()) {
                LazyColumn(Modifier.fillMaxSize().testTag("transcript"), state = listState) {
                    items(rows, key = { it }) { Box(Modifier.fillMaxWidth().height(80.dp)) }
                }
            }
        }
        compose.waitForIdle()
        assertTrue("opens at the newest", following)

        compose.onNodeWithTag("transcript").performTouchInput { swipeDown() }
        compose.waitForIdle()
        assertFalse("the person scrolled into history", following)
        val reading = list.firstVisibleItemIndex
        assertTrue(reading < rows.lastIndex - 20)

        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertFalse(following)
        assertEquals(reading, list.firstVisibleItemIndex)
    }
}
