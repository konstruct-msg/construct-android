package com.construct.messenger.calls

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import com.construct.messenger.diagnostics.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.AddIceObserver
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RTCStatsReport
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import shared.proto.signaling.v1.Webrtc.TurnCredentials

/**
 * [CallMedia] on webrtc-sdk: one peer connection, one audio track, one video transceiver. **Canon:**
 * iOS `WebRTCSession`.
 *
 * Configuration as iOS: unified plan, max-bundle, rtcp-mux required, every transport allowed.
 * ICE servers are only ours — the TURN credentials, or without them our own STUN; never a public
 * one, which would hand a third party the fact and the addresses of every call.
 *
 * Video, as iOS since 2026-10-03: the caller offers a `sendrecv` video section in every call, with
 * no track; the callee takes the offered one up (`adoptOfferedVideo`). Turning the camera on is
 * then a track swap on a sender that already exists — no renegotiation, no crossing offers.
 */
class WebRtcCallMedia private constructor(
    private val context: Context,
    private val factory: PeerConnectionFactory,
    private val audio: CallAudio,
    private val listener: CallMedia.Listener,
) : CallMedia {

    private lateinit var peer: PeerConnection
    private lateinit var source: AudioSource
    private lateinit var track: AudioTrack

    /**
     * WebRTC refuses a candidate before the remote description and does not retry it — a call
     * then sits in `checking`. They wait here and are added when it is set (iOS keeps the same
     * buffer inside `WebRTCSession`; the machine's own buffer covers the time before media exists).
     */
    private var remoteDescriptionSet = false
    private val pendingRemote = mutableListOf<CallIce>()
    @Volatile private var closed = false

    @Volatile private var videoTransceiver: RtpTransceiver? = null
    private val localFrames = VideoSinkHub("self view")
    private val remoteFrames = VideoSinkHub("received")
    /** The camera's track, kept across off/on so the preview and the sender reuse one source. */
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var capturer: CameraVideoCapturer? = null
    private var captureHelper: SurfaceTextureHelper? = null
    /** The camera running now, so asking for it again is not a restart. */
    private var capturingFacing: CameraFacing? = null

    override val canSendVideo: Boolean get() = videoTransceiver != null

    override suspend fun createOffer(): String = offer(iceRestart = false)

    override suspend fun restartIce(): String = offer(iceRestart = true)

    private suspend fun offer(iceRestart: Boolean): String {
        // No `OfferToReceiveVideo`: under Unified Plan a "false" there takes the receive direction
        // off the video transceiver, and the peer's camera would never arrive (iOS, by mutation).
        val constraints = constraints("OfferToReceiveAudio" to "true", "IceRestart" to iceRestart.toString())
        val sdp = sdp { peer.createOffer(it, constraints) }
        setLocal(sdp)
        if (iceRestart) Log.i(TAG, "ICE restart offer created")
        return sdp.description
    }

    override suspend fun createAnswer(): String {
        val sdp = sdp { peer.createAnswer(it, constraints("OfferToReceiveAudio" to "true")) }
        setLocal(sdp)
        return sdp.description
    }

    override suspend fun setRemoteOffer(sdp: String) {
        setRemote(SessionDescription(SessionDescription.Type.OFFER, sdp))
        adoptOfferedVideo()
    }

    override suspend fun setRemoteAnswer(sdp: String) = setRemote(SessionDescription(SessionDescription.Type.ANSWER, sdp))

    override suspend fun addRemoteCandidate(candidate: CallIce) {
        if (!remoteDescriptionSet) {
            pendingRemote += candidate
            Log.i(TAG, "ICE: buffered remote candidate (${pendingRemote.size} pending) ${summary(candidate.sdp)}")
            return
        }
        addNow(candidate)
    }

    override fun setMuted(muted: Boolean) {
        if (!closed) track.setEnabled(!muted)
    }

    override fun setCameraOn(on: Boolean, facing: CameraFacing) {
        if (closed) return
        val sender = videoTransceiver?.sender
        if (sender == null) {
            Log.i(TAG, "camera ${if (on) "on" else "off"} ignored — this call has no video sender")
            return
        }
        if (!on) {
            // No track, no frames; the direction stays sendrecv, so turning it back on is the same swap.
            sender.setTrack(null, false)
            stopCapture()
            return
        }
        val video = localVideoTrack ?: makeLocalVideoTrack()
        if (sender.track()?.id() != video.id()) sender.setTrack(video, false)
        if (capturingFacing == facing) return
        val running = capturer
        if (running != null) switchCamera(running, facing) else startCapture(facing)
    }

    override fun addVideoSink(side: CallVideoSide, sink: VideoSink) {
        hub(side).add(sink)
    }

    override fun removeVideoSink(side: CallVideoSide, sink: VideoSink) {
        hub(side).remove(sink)
    }

    private fun hub(side: CallVideoSide) = if (side == CallVideoSide.LOCAL) localFrames else remoteFrames

    /** Media goes, and the phone's audio returns to what it was before the call. */
    override fun close() {
        if (closed) return
        closed = true
        stopCapture()
        if (videoTransceiver != null) Log.i(TAG, remoteFrames.counter.summary())
        peer.close()
        peer.dispose()
        track.dispose()
        source.dispose()
        localVideoTrack?.dispose()
        videoSource?.dispose()
        captureHelper?.dispose()
        audio.release()
        Log.i(TAG, "media closed")
    }

    // ── Video ───────────────────────────────────────────────────────────────

    /** The caller's video section: `sendrecv` from the first offer, no track until the camera is on. */
    private fun addVideoTransceiver() {
        val init = RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV, listOf("video"))
        videoTransceiver = peer.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, init)
        watchRemoteVideo()
        Log.i(TAG, "video transceiver added (sendrecv, no track)")
    }

    /**
     * Take up the video section of an offer as `sendrecv`, so the camera can be turned on later
     * without renegotiating. Without it the transceiver the offer created stays `recvonly` and this
     * side could watch but never send. Not one of our own: JSEP matches an offered section only to
     * a transceiver made by addTrack, and the answer would carry two video sections. An offer with
     * no video section (an audio-only client) leaves [canSendVideo] false.
     */
    private fun adoptOfferedVideo() {
        if (videoTransceiver != null) return
        val offered = peer.transceivers.firstOrNull { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO } ?: return
        if (!offered.setDirection(RtpTransceiver.RtpTransceiverDirection.SEND_RECV)) {
            Log.w(TAG, "offered video section not taken up")
            return
        }
        videoTransceiver = offered
        watchRemoteVideo()
        Log.i(TAG, "offered video section taken up (sendrecv)")
    }

    /** The receiver's track exists with the transceiver; frames arrive whenever the peer's camera is on. */
    private fun watchRemoteVideo() {
        (videoTransceiver?.receiver?.track() as? VideoTrack)?.addSink(remoteFrames)
    }

    private fun makeLocalVideoTrack(): VideoTrack {
        val source = factory.createVideoSource(/* isScreencast = */ false)
        val video = factory.createVideoTrack("video0", source)
        video.addSink(localFrames)
        videoSource = source
        localVideoTrack = video
        return video
    }

    private fun startCapture(facing: CameraFacing) {
        val source = videoSource ?: return
        val enumerator = Camera2Enumerator(context)
        val name = cameraName(enumerator, facing) ?: run {
            Log.e(TAG, "no $facing camera to capture from")
            return
        }
        val formats = enumerator.getSupportedFormats(name).orEmpty()
        val candidates = formats.map { CallVideoCapture.Candidate(it.width, it.height, it.framerate.min / 1000, it.framerate.max / 1000) }
        val choice = CallVideoCapture.choose(candidates) ?: run {
            Log.e(TAG, "camera $facing: no format can run at ${CallVideoCapture.MAX_FPS} fps or below")
            return
        }
        val c = candidates[choice.index]
        val helper = captureHelper ?: SurfaceTextureHelper.create("CallCapture", WebRtcRuntime.egl.eglBaseContext).also { captureHelper = it }
        val counting = CountingCapturerObserver(source.capturerObserver, VideoFrameCounter("camera"))
        val camera = enumerator.createCapturer(name, CameraEvents(facing)) ?: run {
            Log.e(TAG, "camera $facing: no capturer")
            return
        }
        camera.initialize(helper, context, counting)
        camera.startCapture(c.width, c.height, choice.fps)
        capturer = camera
        capturingFacing = facing
        cameraCounter = counting.counter
        Log.i(TAG, "camera $facing capturing ${c.width}x${c.height}@${choice.fps} (range ${c.minFps}-${c.maxFps})")
    }

    private var cameraCounter: VideoFrameCounter? = null

    /**
     * A flip on the running capturer. (On iOS restarting capture on the same session crashed inside
     * WebRTC; Android's capturer swaps the camera itself — checked both ways on a device.)
     */
    private fun switchCamera(camera: CameraVideoCapturer, facing: CameraFacing) {
        val name = cameraName(Camera2Enumerator(context), facing) ?: run {
            Log.e(TAG, "no $facing camera to switch to")
            return
        }
        capturingFacing = facing
        camera.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFrontCamera: Boolean) { Log.i(TAG, "camera switched to ${if (isFrontCamera) "front" else "back"}") }
            override fun onCameraSwitchError(error: String?) { Log.w(TAG, "camera switch failed: $error") }
        }, name)
    }

    private fun stopCapture() {
        val camera = capturer ?: return
        runCatching { camera.stopCapture() }.onFailure { Log.w(TAG, "camera stop: ${it.message}") }
        camera.dispose()
        capturer = null
        capturingFacing = null
        cameraCounter?.let { Log.i(TAG, it.summary()) }
        cameraCounter = null
    }

    /** What the system does to the camera — taken away, frozen, failed. iOS `CaptureSessionWatcher`. */
    private class CameraEvents(private val facing: CameraFacing) : CameraVideoCapturer.CameraEventsHandler {
        override fun onCameraError(error: String?) { Log.w(TAG, "camera $facing error: $error") }
        override fun onCameraDisconnected() { Log.w(TAG, "camera $facing taken away") }
        override fun onCameraFreezed(error: String?) { Log.w(TAG, "camera $facing frozen: $error") }
        override fun onCameraOpening(cameraName: String?) { Log.d(TAG, "camera $facing opening") }
        override fun onFirstFrameAvailable() { Log.d(TAG, "camera $facing first frame") }
        override fun onCameraClosed() { Log.d(TAG, "camera $facing closed") }
    }

    // ── Negotiation ─────────────────────────────────────────────────────────

    private suspend fun setRemote(description: SessionDescription) {
        suspendCancellableCoroutine { cont ->
            peer.setRemoteDescription(object : SdpCallbacks() {
                override fun onSetSuccess() = cont.resume(Unit)
                override fun onSetFailure(error: String?) = cont.resumeWithException(IllegalStateException("setRemoteDescription: $error"))
            }, description)
        }
        remoteDescriptionSet = true
        val buffered = pendingRemote.toList()
        pendingRemote.clear()
        if (buffered.isNotEmpty()) Log.i(TAG, "ICE: remote SDP applied — adding ${buffered.size} buffered candidate(s)")
        buffered.forEach { runCatching { addNow(it) }.onFailure { e -> Log.w(TAG, "ICE: buffered candidate refused: ${e.message}") } }
    }

    private suspend fun addNow(candidate: CallIce) {
        suspendCancellableCoroutine { cont ->
            peer.addIceCandidate(IceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp), object : AddIceObserver {
                override fun onAddSuccess() = cont.resume(Unit)
                override fun onAddFailure(error: String?) = cont.resumeWithException(IllegalStateException("addIceCandidate: $error"))
            })
        }
        Log.i(TAG, "ICE: added remote candidate ${summary(candidate.sdp)}")
    }

    private suspend fun sdp(create: (SdpObserver) -> Unit): SessionDescription = suspendCancellableCoroutine { cont ->
        create(object : SdpCallbacks() {
            override fun onCreateSuccess(description: SessionDescription?) {
                if (description == null) cont.resumeWithException(IllegalStateException("no SDP")) else cont.resume(description)
            }
            override fun onCreateFailure(error: String?) = cont.resumeWithException(IllegalStateException("create SDP: $error"))
        })
    }

    private suspend fun setLocal(description: SessionDescription) = suspendCancellableCoroutine { cont ->
        peer.setLocalDescription(object : SdpCallbacks() {
            override fun onSetSuccess() = cont.resume(Unit)
            override fun onSetFailure(error: String?) = cont.resumeWithException(IllegalStateException("setLocalDescription: $error"))
        }, description)
    }

    /** WebRTC's callbacks, from its signalling thread; the machine moves each onto its own. */
    private inner class Events : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            listener.onLocalCandidate(CallIce(candidate.sdp, candidate.sdpMid.orEmpty(), candidate.sdpMLineIndex))
        }

        /**
         * `disconnected` is a blip on a phone (a lock, Wi-Fi to mobile) — reconnecting, not the
         * end. Only `failed` means every candidate pair is spent.
         */
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            Log.i(TAG, "iceConnectionState → $state")
            when (state) {
                PeerConnection.IceConnectionState.FAILED -> listener.onFailed()
                PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> listener.onQuality(CallQuality.GOOD)
                PeerConnection.IceConnectionState.DISCONNECTED -> listener.onQuality(CallQuality.RECONNECTING)
                else -> Unit
            }
        }

        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
            Log.i(TAG, "peerConnectionState → $state")
            when (state) {
                PeerConnection.PeerConnectionState.CONNECTED -> {
                    peer.getStats { report -> Log.i(TAG, "transport: ${transportSummary(report)}") }
                    listener.onConnected()
                }
                PeerConnection.PeerConnectionState.FAILED -> listener.onFailed()
                else -> Unit
            }
        }

        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
            Log.i(TAG, "remote track: kind=${receiver.track()?.kind()} enabled=${receiver.track()?.enabled()}")
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState) { Log.d(TAG, "signalingState → $state") }
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) { Log.d(TAG, "iceGatheringState → $state") }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(channel: DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
    }

    private open class SdpCallbacks : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription?) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) = Unit
        override fun onSetFailure(error: String?) = Unit
    }

    /** Builds [WebRtcCallMedia] — the one [CallMedia.Factory] the app uses. */
    @Singleton
    class Factory @Inject constructor(
        @ApplicationContext private val context: Context,
        private val audio: CallAudio,
    ) : CallMedia.Factory {

        override fun create(role: CallMedia.Role, turn: TurnCredentials?, listener: CallMedia.Listener): CallMedia {
            // Without the microphone the call would connect and carry nothing from us. The call
            // screen asks before calling or answering; reaching here without it is a bug there.
            check(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                "RECORD_AUDIO not granted"
            }
            val factory = WebRtcRuntime.factory(context)
            val media = WebRtcCallMedia(context, factory, audio, listener)
            val config = PeerConnection.RTCConfiguration(iceServers(turn)).apply {
                iceTransportsType = PeerConnection.IceTransportsType.ALL
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            }
            media.peer = factory.createPeerConnection(config, media.Events()) ?: error("createPeerConnection returned null")
            audio.acquire()
            media.source = factory.createAudioSource(MediaConstraints())
            media.track = factory.createAudioTrack("audio0", media.source)
            media.peer.addTrack(media.track, listOf("audio"))
            // The callee's comes from the offer instead (`adoptOfferedVideo`).
            if (role == CallMedia.Role.CALLER) media.addVideoTransceiver()
            Log.i(TAG, "media created role=$role ice=${if (turn != null && turn.urlsCount > 0) "TURN ${turn.urlsCount} url(s)" else "own STUN"}")
            return media
        }

        override fun cameraAllowed(): Boolean =
            context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val TAG = "CallMedia"

        /** Ours only. TURN answers STUN binding too, so with credentials nothing else is needed. */
        const val OWN_STUN = "stun:ams.konstruct.cc:3478"

        fun iceServers(turn: TurnCredentials?): List<PeerConnection.IceServer> =
            if (turn != null && turn.urlsCount > 0) {
                listOf(PeerConnection.IceServer.builder(turn.urlsList).setUsername(turn.username).setPassword(turn.credential).createIceServer())
            } else {
                listOf(PeerConnection.IceServer.builder(OWN_STUN).createIceServer())
            }

        private fun constraints(vararg pairs: Pair<String, String>) = MediaConstraints().apply {
            pairs.forEach { (k, v) -> mandatory.add(MediaConstraints.KeyValuePair(k, v)) }
        }

        /** By the direction it faces — what keeps "front" the front whatever the camera ids are. */
        private fun cameraName(enumerator: Camera2Enumerator, facing: CameraFacing): String? =
            enumerator.deviceNames.firstOrNull { if (facing == CameraFacing.FRONT) enumerator.isFrontFacing(it) else enumerator.isBackFacing(it) }

        /**
         * What protects the media: DTLS version and cipher, the key-exchange group (X25519MLKEM768
         * when the PQC trial took), the SRTP cipher. Read from the `transport` stats; a field this
         * WebRTC build does not report is left out.
         */
        fun transportSummary(report: RTCStatsReport): String = report.statsMap.values
            .filter { it.type == "transport" }
            .joinToString("; ") { stats ->
                listOf("tlsVersion", "dtlsCipher", "tlsGroup", "srtpCipher", "dtlsRole", "dtlsState")
                    .mapNotNull { key -> stats.members[key]?.let { "$key=$it" } }
                    .joinToString(" ")
            }

        /** "typ relay udp" — what a candidate is, without its address. */
        fun summary(candidate: String): String {
            val parts = candidate.split(' ')
            val typ = parts.indexOf("typ").takeIf { it >= 0 && it + 1 < parts.size }?.let { parts[it + 1] } ?: "?"
            val proto = parts.getOrNull(2)?.lowercase() ?: "?"
            return "typ $typ $proto"
        }
    }
}
