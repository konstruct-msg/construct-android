package com.construct.messenger.ui.screens.calls

import com.construct.messenger.data.model.CallUi
import com.construct.messenger.ui.screens.calls.VideoCallStage.Pane
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the video call screen shows where. Each test names the mutation that reddens it. **Canon:** iOS `VideoCallStageTests`. */
class VideoCallStageTest {

    private fun stage(local: Boolean, remote: Boolean, connecting: Boolean = false, ended: Boolean = false, swapped: Boolean = false) =
        VideoCallStage.make(CallUi.Video(canSend = true, cameraOn = local, sending = local, peerCameraOn = remote), connecting, ended, swapped)

    /** Mutation: return a stage for (false, false) — an audio call opens on an empty video screen. */
    @Test
    fun `no camera is the audio screen`() = assertNull(stage(local = false, remote = false))

    /** An ended call shows why it ended, on the audio screen, whatever the cameras were doing. */
    @Test
    fun `an ended call leaves the video screen`() = assertNull(stage(local = true, remote = true, ended = true))

    @Test
    fun `both cameras put the peer big and us small`() =
        assertEquals(VideoCallStage(Pane.REMOTE_VIDEO, Pane.LOCAL_VIDEO), stage(local = true, remote = true))

    /** Mutation: ignore `swapped` — a tap on the window does nothing. */
    @Test
    fun `a swap trades the places`() =
        assertEquals(VideoCallStage(Pane.LOCAL_VIDEO, Pane.REMOTE_VIDEO), stage(local = true, remote = true, swapped = true))

    /** Mutation: show the avatar while connecting — the caller of a video call stares at someone who has not answered. */
    @Test
    fun `while ringing our camera fills the screen`() =
        assertEquals(VideoCallStage(Pane.LOCAL_VIDEO, null), stage(local = true, remote = false, connecting = true))

    @Test
    fun `the peer's camera off shows their avatar`() =
        assertEquals(VideoCallStage(Pane.REMOTE_AVATAR, Pane.LOCAL_VIDEO), stage(local = true, remote = false))

    @Test
    fun `our camera off keeps an empty window`() =
        assertEquals(VideoCallStage(Pane.REMOTE_VIDEO, Pane.LOCAL_CAMERA_OFF), stage(local = false, remote = true))

    /** A camera the system took away in the background counts as off here too, as it does for the peer. */
    @Test
    fun `a backgrounded camera is shown off`() {
        val video = CallUi.Video(canSend = true, cameraOn = true, sending = false, peerCameraOn = true)
        assertEquals(Pane.LOCAL_CAMERA_OFF, VideoCallStage.make(video, isConnecting = false, isEnded = false, swapped = false)?.small)
    }

    /** Mutation: `canSwap` always true — a swap puts "camera off" on the whole screen. */
    @Test
    fun `only two faces swap`() {
        assertTrue(VideoCallStage(Pane.REMOTE_VIDEO, Pane.LOCAL_VIDEO).canSwap)
        assertFalse(VideoCallStage(Pane.REMOTE_VIDEO, Pane.LOCAL_CAMERA_OFF).canSwap)
        assertFalse(VideoCallStage(Pane.REMOTE_AVATAR, Pane.LOCAL_VIDEO).canSwap)
        assertFalse(VideoCallStage(Pane.LOCAL_VIDEO, null).canSwap)
    }

    /** Mutation: drop the TalkBack term — a TalkBack user loses the end button three seconds in. */
    @Test
    fun `controls stay for TalkBack`() {
        val faces = VideoCallStage(Pane.REMOTE_VIDEO, Pane.LOCAL_VIDEO)
        assertTrue(faces.controlsAutoHide(isConnecting = false, talkBack = false))
        assertFalse(faces.controlsAutoHide(isConnecting = false, talkBack = true))
    }

    @Test
    fun `controls stay while there is no face to see`() {
        assertFalse(VideoCallStage(Pane.REMOTE_AVATAR, Pane.LOCAL_VIDEO).controlsAutoHide(isConnecting = false, talkBack = false))
        assertFalse(VideoCallStage(Pane.LOCAL_VIDEO, null).controlsAutoHide(isConnecting = true, talkBack = false))
    }

    @Test
    fun `the window lands in the nearest corner`() {
        val w = 390f
        val h = 844f
        assertEquals(PreviewCorner.TOP_START, PreviewCorner.nearest(20f, 30f, w, h))
        assertEquals(PreviewCorner.TOP_END, PreviewCorner.nearest(380f, 30f, w, h))
        assertEquals(PreviewCorner.BOTTOM_START, PreviewCorner.nearest(20f, 800f, w, h))
        assertEquals(PreviewCorner.BOTTOM_END, PreviewCorner.nearest(380f, 800f, w, h))
        // A flick past the edge still lands in a corner.
        assertEquals(PreviewCorner.BOTTOM_START, PreviewCorner.nearest(-400f, 2000f, w, h))
    }
}
