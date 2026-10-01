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
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RTCStatsReport
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import shared.proto.signaling.v1.Webrtc.TurnCredentials

/**
 * [CallMedia] on webrtc-sdk: one peer connection, one audio track, no video. **Canon:** iOS
 * `WebRTCSession`.
 *
 * Configuration as iOS: unified plan, max-bundle, rtcp-mux required, every transport allowed.
 * ICE servers are only ours — the TURN credentials, or without them our own STUN; never a public
 * one, which would hand a third party the fact and the addresses of every call.
 */
class WebRtcCallMedia private constructor(
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

    override suspend fun createOffer(): String = offer(iceRestart = false)

    override suspend fun restartIce(): String = offer(iceRestart = true)

    private suspend fun offer(iceRestart: Boolean): String {
        val constraints = constraints("OfferToReceiveAudio" to "true", "OfferToReceiveVideo" to "false", "IceRestart" to iceRestart.toString())
        val sdp = sdp { peer.createOffer(it, constraints) }
        setLocal(sdp)
        if (iceRestart) Log.i(TAG, "ICE restart offer created")
        return sdp.description
    }

    override suspend fun createAnswer(): String {
        val sdp = sdp { peer.createAnswer(it, constraints("OfferToReceiveAudio" to "true", "OfferToReceiveVideo" to "false")) }
        setLocal(sdp)
        return sdp.description
    }

    override suspend fun setRemoteOffer(sdp: String) = setRemote(SessionDescription(SessionDescription.Type.OFFER, sdp))

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

    /** Media goes, and the phone's audio returns to what it was before the call. */
    override fun close() {
        if (closed) return
        closed = true
        peer.close()
        peer.dispose()
        track.dispose()
        source.dispose()
        audio.release()
        Log.i(TAG, "media closed")
    }

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
            val media = WebRtcCallMedia(audio, listener)
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
            Log.i(TAG, "media created role=$role ice=${if (turn != null && turn.urlsCount > 0) "TURN ${turn.urlsCount} url(s)" else "own STUN"}")
            return media
        }
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
