package com.construct.messenger.calls

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SoftwareVideoDecoderFactory
import org.webrtc.SoftwareVideoEncoderFactory

/**
 * How a call negotiates its video section, on real peer connections. **Canon:** iOS
 * `CallVideoNegotiationTests`. Emulator only, as [WebRtcLoopbackTest].
 */
@RunWith(AndroidJUnit4::class)
class CallVideoNegotiationTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    /**
     * What an iOS build with video calls offers (`WebRTCSession`, caller): audio, and a `sendrecv`
     * video transceiver with no track. Its own factory, with codecs, so the video section is real.
     *
     * Mutation: build [WebRtcRuntime]'s factory without video codecs again — the process aborts
     * inside setRemoteDescription (libc++ "front() called on an empty vector"), as 0.16.0 did on
     * answering every iOS call once iOS offered video.
     */
    @Test
    fun anAudioCallAnswersAnOfferWithVideoAndConnects(): Unit = runBlocking {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        WebRtcRuntime.factory(context) // PeerConnectionFactory.initialize, once per process
        val iosFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(SoftwareVideoEncoderFactory())
            .setVideoDecoderFactory(SoftwareVideoDecoderFactory())
            .createPeerConnectionFactory()
        val ios = RawPeer(iosFactory)
        ios.pc.addTrack(iosFactory.createAudioTrack("ios-audio", iosFactory.createAudioSource(MediaConstraints())), listOf("audio"))
        ios.pc.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV, listOf("video")),
        )

        val android = Side()
        val media = WebRtcCallMedia.Factory(context, CallAudio(context)).create(CallMedia.Role.CALLEE, null, android)
        try {
            val offer = create { ios.pc.createOffer(it, MediaConstraints()) }
            set { ios.pc.setLocalDescription(it, offer) }
            assertEquals("the offer carries a live video section", "9", port(offer.description, "video"))

            media.setRemoteOffer(offer.description)
            val answer = media.createAnswer()
            assertEquals("one audio and one video section", listOf("audio", "video"), sections(answer))
            assertTrue("the callee takes the offered video up", media.canSendVideo)
            assertTrue(videoSection(answer)!!, videoSection(answer)!!.contains("a=sendrecv"))
            set { ios.pc.setRemoteDescription(it, SessionDescription(SessionDescription.Type.ANSWER, answer)) }

            trickle(ios, media, android)
            assertTrue("iOS side connected", ios.connected.await(1, TimeUnit.SECONDS))
            assertTrue("Android side connected", android.connected.await(1, TimeUnit.SECONDS))
        } finally {
            media.close()
            ios.pc.dispose()
            iosFactory.dispose()
        }
    }

    private fun media(role: CallMedia.Role, side: Side = Side()): CallMedia {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        return WebRtcCallMedia.Factory(context, CallAudio(context)).create(role, null, side)
    }

    /**
     * Mutation: add the transceiver only when the call starts with the camera — turning it on
     * mid-call would need an offer. Mutation: put `"OfferToReceiveVideo" to "false"` back — the
     * section goes out `sendonly`.
     */
    @Test
    fun everyCallOffersVideoBothWays(): Unit = runBlocking {
        val caller = media(CallMedia.Role.CALLER)
        try {
            assertTrue(caller.canSendVideo)
            val video = videoSection(caller.createOffer())
            assertTrue("no video section in the offer", video != null)
            assertTrue(video!!, video.contains("a=sendrecv"))
        } finally {
            caller.close()
        }
    }

    /** Mutation: drop `adoptOfferedVideo()` — the callee answers `recvonly` and can never send. */
    @Test
    fun theCalleeTakesUpTheOfferedVideo(): Unit = runBlocking {
        val caller = media(CallMedia.Role.CALLER)
        val callee = media(CallMedia.Role.CALLEE)
        try {
            assertFalse("nothing to take up before the offer", callee.canSendVideo)
            callee.setRemoteOffer(caller.createOffer())
            assertTrue(callee.canSendVideo)
            val answer = callee.createAnswer()
            assertTrue(videoSection(answer)!!, videoSection(answer)!!.contains("a=sendrecv"))
            assertEquals("one video section, not two", listOf("audio", "video"), sections(answer))
        } finally {
            caller.close()
            callee.close()
        }
    }

    /** An audio-only caller — Android 0.16 — gets an audio-only answer, and the callee has no camera to offer. */
    @Test
    fun anAudioOnlyOfferStaysAudioOnly(): Unit = runBlocking {
        WebRtcRuntime.factory(context)
        val oldFactory = PeerConnectionFactory.builder().createPeerConnectionFactory()
        val old = RawPeer(oldFactory)
        old.pc.addTrack(oldFactory.createAudioTrack("old-audio", oldFactory.createAudioSource(MediaConstraints())), listOf("audio"))
        val callee = media(CallMedia.Role.CALLEE)
        try {
            val offer = create { old.pc.createOffer(it, MediaConstraints()) }
            assertEquals(listOf("audio"), sections(offer.description))
            callee.setRemoteOffer(offer.description)
            assertFalse(callee.canSendVideo)
            assertEquals(listOf("audio"), sections(callee.createAnswer()))
            callee.setCameraOn(true, CameraFacing.FRONT) // nothing to feed: ignored, not a crash
        } finally {
            callee.close()
            old.pc.dispose()
            oldFactory.dispose()
        }
    }

    /**
     * The whole way: the caller turns the emulator's camera on mid-call, with no renegotiation,
     * and its frames reach the callee's screen side. Then off, and on again — the swap both ways.
     */
    @Test
    fun cameraFramesReachThePeerWithoutRenegotiating(): Unit = runBlocking {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        val callerSide = Side()
        val calleeSide = Side()
        val caller = media(CallMedia.Role.CALLER, callerSide)
        val callee = media(CallMedia.Role.CALLEE, calleeSide)
        val received = CountDownLatch(5)
        val ownPreview = CountDownLatch(1)
        callee.addVideoSink(CallVideoSide.REMOTE) { received.countDown() }
        caller.addVideoSink(CallVideoSide.LOCAL) { ownPreview.countDown() }
        try {
            callee.setRemoteOffer(caller.createOffer())
            caller.setRemoteAnswer(callee.createAnswer())
            trickleMedia(caller, callerSide, callee, calleeSide)
            assertTrue("connected", callerSide.connected.await(1, TimeUnit.SECONDS) && calleeSide.connected.await(1, TimeUnit.SECONDS))

            caller.setCameraOn(true, CameraFacing.FRONT)
            assertTrue("the caller sees itself", ownPreview.await(10, TimeUnit.SECONDS))
            assertTrue("the callee receives the caller's camera", received.await(15, TimeUnit.SECONDS))

            caller.setCameraOn(false, CameraFacing.FRONT)
            val again = CountDownLatch(3)
            callee.addVideoSink(CallVideoSide.REMOTE) { again.countDown() }
            caller.setCameraOn(true, CameraFacing.BACK)
            assertTrue("on again, the back camera this time", again.await(15, TimeUnit.SECONDS))
        } finally {
            caller.close()
            callee.close()
        }
    }

    private class Side : CallMedia.Listener {
        val connected = CountDownLatch(1)
        val candidates = mutableListOf<CallIce>()
        override fun onLocalCandidate(candidate: CallIce) { synchronized(candidates) { candidates += candidate } }
        override fun onConnected() = connected.countDown()
        override fun onFailed() = Unit
        override fun onQuality(quality: CallQuality) = Unit
    }

    /** A bare peer connection standing in for the other platform. */
    private class RawPeer(factory: PeerConnectionFactory) : PeerConnection.Observer {
        val connected = CountDownLatch(1)
        val candidates = mutableListOf<IceCandidate>()
        val pc: PeerConnection = factory.createPeerConnection(
            PeerConnection.RTCConfiguration(WebRtcCallMedia.iceServers(null)).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            },
            this,
        )!!

        override fun onIceCandidate(c: IceCandidate) { synchronized(candidates) { candidates += c } }
        override fun onConnectionChange(s: PeerConnection.PeerConnectionState) {
            if (s == PeerConnection.PeerConnectionState.CONNECTED) connected.countDown()
        }
        override fun onSignalingChange(s: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) = Unit
        override fun onIceConnectionReceivingChange(b: Boolean) = Unit
        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) = Unit
        override fun onAddStream(s: MediaStream) = Unit
        override fun onRemoveStream(s: MediaStream) = Unit
        override fun onDataChannel(d: DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(r: RtpReceiver, s: Array<out MediaStream>) = Unit
    }

    /** Whatever each side gathered goes to the other, until both say connected. */
    private suspend fun trickle(raw: RawPeer, media: CallMedia, side: Side) {
        val deadline = System.currentTimeMillis() + 20_000
        var fromRaw = 0
        var fromMedia = 0
        while (System.currentTimeMillis() < deadline && (raw.connected.count > 0 || side.connected.count > 0)) {
            val a = synchronized(raw.candidates) { raw.candidates.drop(fromRaw) }
            a.forEach { media.addRemoteCandidate(CallIce(it.sdp, it.sdpMid, it.sdpMLineIndex)) }
            fromRaw += a.size
            val b = synchronized(side.candidates) { side.candidates.drop(fromMedia) }
            b.forEach { raw.pc.addIceCandidate(IceCandidate(it.sdpMid, it.sdpMLineIndex, it.sdp)) }
            fromMedia += b.size
            Thread.sleep(100)
        }
    }

    private suspend fun trickleMedia(a: CallMedia, aSide: Side, b: CallMedia, bSide: Side) {
        val deadline = System.currentTimeMillis() + 20_000
        var fromA = 0
        var fromB = 0
        while (System.currentTimeMillis() < deadline && (aSide.connected.count > 0 || bSide.connected.count > 0)) {
            val x = synchronized(aSide.candidates) { aSide.candidates.drop(fromA) }
            x.forEach { b.addRemoteCandidate(it) }
            fromA += x.size
            val y = synchronized(bSide.candidates) { bSide.candidates.drop(fromB) }
            y.forEach { a.addRemoteCandidate(it) }
            fromB += y.size
            Thread.sleep(100)
        }
    }

    private suspend fun create(block: (SdpObserver) -> Unit): SessionDescription = suspendCancellableCoroutine { cont ->
        block(object : SdpObserver {
            override fun onCreateSuccess(d: SessionDescription) = cont.resume(d)
            override fun onCreateFailure(e: String?) = cont.resumeWithException(IllegalStateException(e))
            override fun onSetSuccess() = Unit
            override fun onSetFailure(e: String?) = Unit
        })
    }

    private suspend fun set(block: (SdpObserver) -> Unit): Unit = suspendCancellableCoroutine { cont ->
        block(object : SdpObserver {
            override fun onCreateSuccess(d: SessionDescription) = Unit
            override fun onCreateFailure(e: String?) = Unit
            override fun onSetSuccess() = cont.resume(Unit)
            override fun onSetFailure(e: String?) = cont.resumeWithException(IllegalStateException(e))
        })
    }

    private companion object {
        fun sections(sdp: String) = sdp.lines().filter { it.startsWith("m=") }.map { it.removePrefix("m=").substringBefore(' ') }

        /** The video section's lines, or null when the SDP has none. */
        fun videoSection(sdp: String): String? {
            val lines = sdp.lines()
            val start = lines.indexOfFirst { it.startsWith("m=video") }.takeIf { it >= 0 } ?: return null
            val end = lines.drop(start + 1).indexOfFirst { it.startsWith("m=") }.let { if (it < 0) lines.size else start + 1 + it }
            return lines.subList(start, end).joinToString("\n")
        }

        fun port(sdp: String, kind: String) = sdp.lines().first { it.startsWith("m=$kind ") }.split(' ')[1]
    }
}
