package com.construct.messenger.ui.screens.synaps

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.construct.messenger.data.model.Contact
import com.construct.messenger.ui.theme.KonstructMessengerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The cloud's gestures: a pinch works wherever the fingers land and zooms around them, a tap on a
 * circle opens it, a drag does not.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = Application::class)
class SynapsCloudGestureTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val opened = mutableListOf<String>()

    private fun show() {
        val contacts = listOf("Alice", "Bob", "Carol", "Dan", "Eve", "Frank", "Grace")
            .mapIndexed { i, n -> Contact(userId = "u$i", displayName = n) }
        // Alice the most active: she takes the centre.
        val metrics = contacts.mapIndexed { i, c -> c.userId to ContactMetrics(frequencyScore = 1f - i / 10f) }.toMap()
        compose.setContent {
            KonstructMessengerTheme {
                SynapsCloud(
                    contacts = contacts,
                    metrics = metrics,
                    blockedIds = emptySet(),
                    topInset = 0.dp,
                    onOpen = { opened += it },
                    modifier = Modifier.testTag("cloud"),
                )
            }
        }
        compose.waitForIdle()
    }

    private fun aliceCentre(): Offset {
        val b = compose.onNodeWithText("Alice").fetchSemanticsNode().boundsInRoot
        val cloud = compose.onNodeWithTag("cloud").fetchSemanticsNode().boundsInRoot
        // The circle sits just above the name, centred on it.
        return Offset(b.center.x - cloud.left, b.top - cloud.top - 30f)
    }

    private fun bobWidth(): Float = compose.onNodeWithText("Bob").fetchSemanticsNode().boundsInRoot.width

    @Test
    fun pinchWithAFingerOnACircleZooms() {
        show()
        val before = bobWidth()
        val c = aliceCentre()
        compose.onNodeWithTag("cloud").performTouchInput {
            pinch(
                start0 = c, end0 = c,
                start1 = c + Offset(300f, 300f), end1 = c + Offset(120f, 120f),
                durationMillis = 400,
            )
        }
        compose.waitForIdle()
        assertTrue("Bob ${bobWidth()} should be narrower than $before after a pinch in", bobWidth() < before * 0.9f)
    }

    private fun bobCentre(): Offset = compose.onNodeWithText("Bob").fetchSemanticsNode().boundsInRoot.center

    /** A pinch zooms around the fingers: the contact between them stays between them. */
    @Test
    fun pinchZoomsAroundTheFingers() {
        show()
        val cloud = compose.onNodeWithTag("cloud").fetchSemanticsNode().boundsInRoot
        val bob = bobCentre()
        val at = Offset(bob.x - cloud.left, bob.y - cloud.top)
        compose.onNodeWithTag("cloud").performTouchInput {
            pinch(
                start0 = at - Offset(40f, 0f), end0 = at - Offset(160f, 0f),
                start1 = at + Offset(40f, 0f), end1 = at + Offset(160f, 0f),
                durationMillis = 400,
            )
        }
        compose.waitForIdle()
        val moved = (bobCentre() - bob).getDistance()
        assertTrue("Bob moved $moved px from under the fingers", moved < 60f)
    }

    @Test
    fun aTapOnACircleOpensIt() {
        show()
        val c = aliceCentre()
        compose.onNodeWithTag("cloud").performTouchInput { click(c) }
        compose.waitForIdle()
        assertEquals(listOf("u0"), opened)
    }

    @Test
    fun aDragFromACircleDoesNotOpenIt() {
        show()
        val c = aliceCentre()
        compose.onNodeWithTag("cloud").performTouchInput { swipe(c, c + Offset(200f, 0f), durationMillis = 300) }
        compose.waitForIdle()
        assertTrue("opened $opened", opened.isEmpty())
    }
}
