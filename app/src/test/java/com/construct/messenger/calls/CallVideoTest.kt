package com.construct.messenger.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import shared.proto.signaling.v1.Webrtc.CallType
import shared.proto.signaling.v1.Webrtc.MediaType
import shared.proto.signaling.v1.Webrtc.WebRTCSignal

/**
 * The camera state the peer is told, the `MediaUpdate` that carries it, and the capture format.
 * Each test names the mutation that reddens it. **Canon:** iOS `CallVideoTests`.
 */
class CallVideoTest {

    // ── What the peer is told ───────────────────────────────────────────────

    /** Mutation: drop `!inBackground` — the peer watches a frozen frame while we are away. */
    @Test
    fun `a backgrounded camera is announced off`() {
        val state = CallVideoState(canSend = true, localCameraOn = true)
        assertTrue(state.announcedCameraOn)
        assertFalse(state.copy(inBackground = true).announcedCameraOn)
    }

    /** Mutation: drop `canSend` — a callee offered audio only claims a camera it cannot send. */
    @Test
    fun `no sender means no camera`() {
        assertFalse(CallVideoState(canSend = false, localCameraOn = true).announcedCameraOn)
    }

    /**
     * The iOS build 716 desync: the callee turns the camera on and the caller must hear of it.
     * Mutation: read "before" after the change in `apply` — this returns null.
     */
    @Test
    fun `turning the camera on is announced`() {
        val on = CallVideoState(canSend = true).apply(true) { it.copy(localCameraOn = true) }
        assertEquals(true, on.announce)
        assertEquals(false, on.state.apply(true) { it.copy(localCameraOn = false) }.announce)
    }

    @Test
    fun `a flip is not announced`() {
        val change = CallVideoState(canSend = true, localCameraOn = true).apply(true) { it.copy(facing = it.facing.flipped) }
        assertNull(change.announce)
        assertEquals(CameraFacing.BACK, change.state.facing)
    }

    /** The callee's sender appears with the offer: a camera asked for before then is announced when it does. */
    @Test
    fun `a sender arriving with the camera on is announced`() {
        val asked = CallVideoState().apply(false) { it.copy(localCameraOn = true) }
        assertNull(asked.announce)
        assertEquals(true, asked.state.apply(true) { it }.announce)
    }

    /** No media yet (null): what was known about the sender stays. */
    @Test
    fun `no media leaves canSend as it was`() {
        assertTrue(CallVideoState(canSend = true).apply(null) { it }.state.canSend)
    }

    /** Mutation: return false — the caller of a video call sees the callee's avatar. */
    @Test
    fun `a video call is answered with the camera`() {
        assertTrue(CallVideoSignal.answersWithCamera(CallType.CALL_TYPE_VIDEO))
        assertFalse(CallVideoSignal.answersWithCamera(CallType.CALL_TYPE_AUDIO))
        assertFalse(CallVideoSignal.answersWithCamera(CallType.CALL_TYPE_UNSPECIFIED))
    }

    @Test
    fun `the offer says video when the call starts with or has the camera`() {
        assertEquals(CallType.CALL_TYPE_AUDIO, CallVideoSignal.offerCallType(CallVideoState(), startsWithCamera = false))
        assertEquals(CallType.CALL_TYPE_VIDEO, CallVideoSignal.offerCallType(CallVideoState(), startsWithCamera = true))
        assertEquals(
            CallType.CALL_TYPE_VIDEO,
            CallVideoSignal.offerCallType(CallVideoState(canSend = true, localCameraOn = true), startsWithCamera = false),
        )
    }

    /** An older client never sends `MediaUpdate`; its avatar is what it is sending. */
    @Test
    fun `the peer starts with the camera off`() {
        assertFalse(CallVideoState().remoteCameraOn)
    }

    // ── MediaUpdate ─────────────────────────────────────────────────────────

    /** Mutation: always return true — turning the camera off leaves the peer on a frozen frame. */
    @Test
    fun `the update round-trips both ways, through the wire`() {
        for (on in listOf(true, false)) {
            val signal = WebRTCSignal.newBuilder().setCallId("c").setMediaUpdate(CallVideoSignal.mediaUpdate(on, 7)).build()
            val decoded = WebRTCSignal.parseFrom(signal.toByteArray())
            assertEquals(WebRTCSignal.SignalCase.MEDIA_UPDATE, decoded.signalCase)
            assertEquals(on, CallVideoSignal.remoteCameraOn(decoded.mediaUpdate))
        }
    }

    /** Mutation: drop the `MEDIA_TYPE_VIDEO` guard — a microphone mute hides the peer's face. */
    @Test
    fun `an update about another media is not about the camera`() {
        val off = CallVideoSignal.mediaUpdate(false, 1)
        assertNull(CallVideoSignal.remoteCameraOn(off.toBuilder().setMediaType(MediaType.MEDIA_TYPE_AUDIO).build()))
        assertNull(CallVideoSignal.remoteCameraOn(off.toBuilder().setMediaType(MediaType.MEDIA_TYPE_SCREEN).build()))
    }

    // ── Capture format ──────────────────────────────────────────────────────

    private fun format(w: Int, h: Int, min: Int = 1, max: Int = 30) = CallVideoCapture.Candidate(w, h, min, max)

    /** Mutation: pick the largest format overall — 1080p goes up the uplink budgeted for 720p. */
    @Test
    fun `the pick is the largest within 720p`() {
        val formats = listOf(format(640, 480), format(1920, 1080), format(1280, 720), format(352, 288))
        assertEquals(2, CallVideoCapture.choose(formats)?.index)
    }

    @Test
    fun `a camera with nothing small gives its smallest`() {
        assertEquals(1, CallVideoCapture.choose(listOf(format(3840, 2160), format(1920, 1080)))?.index)
        assertNull(CallVideoCapture.choose(emptyList()))
    }

    /** The iOS build 716 crash. Mutation: drop `canRun` from the filter — the 240 fps format is picked, at 30. */
    @Test
    fun `a high-speed twin is never picked`() {
        val formats = listOf(format(640, 480), format(1280, 720, 240, 240))
        val pick = CallVideoCapture.choose(formats)!!
        assertEquals(0, pick.index)
        assertTrue(pick.fps in formats[pick.index].minFps..formats[pick.index].maxFps)
    }

    /** Mutation: drop the tie-break — whichever came first wins. */
    @Test
    fun `of equals the ordinary format wins`() {
        assertEquals(1, CallVideoCapture.choose(listOf(format(1280, 720, max = 60), format(1280, 720)))?.index)
    }

    @Test
    fun `the frame rate is capped at 30`() {
        assertEquals(30, CallVideoCapture.choose(listOf(format(1280, 720, max = 60)))?.fps)
        assertEquals(24, CallVideoCapture.choose(listOf(format(1280, 720, max = 24)))?.fps)
    }

    // ── Sound ───────────────────────────────────────────────────────────────

    @Test
    fun `the camera coming on moves the sound off the earpiece, and only then`() {
        assertTrue(CallVideoAudio.movesToSpeaker(wasSending = false, isSending = true, outputIsEarpiece = true))
        assertFalse("a headset is left alone", CallVideoAudio.movesToSpeaker(false, true, outputIsEarpiece = false))
        assertFalse("already sending", CallVideoAudio.movesToSpeaker(true, true, true))
        assertFalse("turning it off", CallVideoAudio.movesToSpeaker(true, false, true))
    }
}
