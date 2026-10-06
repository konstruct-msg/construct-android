package com.construct.messenger.data.repository

import com.construct.messenger.calls.CallEndReason
import com.construct.messenger.calls.CallError
import com.construct.messenger.calls.CallManager
import com.construct.messenger.calls.CallQuality
import com.construct.messenger.calls.CallState
import com.construct.messenger.calls.CallTelecom
import com.construct.messenger.calls.CallVideoSide
import com.construct.messenger.calls.CallVideoState
import com.construct.messenger.calls.CameraFacing
import com.construct.messenger.calls.WebRtcRuntime
import com.construct.messenger.calls.session
import com.construct.messenger.data.model.CallStartError
import com.construct.messenger.data.model.CallUi
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.webrtc.EglBase
import org.webrtc.VideoSink
import shared.proto.signaling.v1.Webrtc.HangupReason

/** The call, for the screens. */
interface CallsRepository {
    /** Null while there is no call (and after an ended one has been shown). */
    val call: StateFlow<CallUi?>
    val startError: StateFlow<CallStartError?>
    /** [video]: the video button — the call starts with the camera. */
    fun start(peerId: String, video: Boolean = false)
    fun answer()
    fun end()
    fun setMuted(muted: Boolean)
    fun setSpeaker(on: Boolean)
    fun setCameraOn(on: Boolean)
    fun switchCamera()
    fun dismissEnded()
    fun clearStartError()

    /** The GL context video is drawn with — the one it is decoded with. */
    val videoContext: EglBase.Context

    /** Where one side's frames are drawn; [local] is our camera. */
    fun addVideoSink(local: Boolean, sink: VideoSink)
    fun removeVideoSink(local: Boolean, sink: VideoSink)
}

@Singleton
class CallsRepositoryImpl @Inject constructor(
    private val calls: CallManager,
    private val telecom: CallTelecom,
) : CallsRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val muted = MutableStateFlow(false)

    override val call: StateFlow<CallUi?> =
        combine(calls.state, calls.quality, muted, telecom.speaker, calls.video) { state, quality, isMuted, speaker, video ->
            CallUiMapping.ui(state, quality == CallQuality.RECONNECTING, isMuted, speaker, video)
        }.stateIn(scope, SharingStarted.Eagerly, CallUiMapping.ui(calls.state.value, false, false, false))

    override val startError: StateFlow<CallStartError?> = calls.lastError
        .map { it?.let(CallUiMapping::startError) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    override fun start(peerId: String, video: Boolean) {
        muted.value = false
        calls.startOutgoingCall(peerId, video)
    }

    override fun answer() {
        muted.value = false
        calls.answer()
    }

    override fun end() = calls.end()

    override fun setMuted(muted: Boolean) {
        this.muted.value = muted
        calls.setMuted(muted)
    }

    override fun setSpeaker(on: Boolean) = telecom.setSpeaker(on)

    override fun setCameraOn(on: Boolean) = calls.setCameraOn(on)

    override fun switchCamera() = calls.switchCamera()

    override val videoContext: EglBase.Context get() = WebRtcRuntime.egl.eglBaseContext

    override fun addVideoSink(local: Boolean, sink: VideoSink) {
        calls.addVideoSink(if (local) CallVideoSide.LOCAL else CallVideoSide.REMOTE, sink)
    }

    override fun removeVideoSink(local: Boolean, sink: VideoSink) {
        calls.removeVideoSink(if (local) CallVideoSide.LOCAL else CallVideoSide.REMOTE, sink)
    }

    override fun dismissEnded() = calls.dismissEnded()

    override fun clearStartError() = calls.clearLastError()
}

/** The state machine's vocabulary in the screen's. Pure, and tested. */
object CallUiMapping {
    fun ui(state: CallState, reconnecting: Boolean, muted: Boolean, speaker: Boolean, video: CallVideoState = CallVideoState()): CallUi? {
        val session = state.session() ?: return null
        val phase = when (state) {
            is CallState.Incoming -> CallUi.Phase.INCOMING
            is CallState.Dialing -> CallUi.Phase.CALLING
            is CallState.Ringing -> CallUi.Phase.RINGING
            is CallState.Connecting -> CallUi.Phase.CONNECTING
            is CallState.Active -> CallUi.Phase.ACTIVE
            is CallState.Ended -> CallUi.Phase.ENDED
            CallState.Idle -> return null
        }
        return CallUi(
            callId = session.id,
            peerId = session.peerUserId,
            peerName = session.peerName,
            incoming = session.isIncoming,
            phase = phase,
            ended = (state as? CallState.Ended)?.let { endedAs(it.reason) },
            reconnecting = reconnecting && phase == CallUi.Phase.ACTIVE,
            muted = muted,
            speaker = speaker,
            activeSinceMs = (state as? CallState.Active)?.sinceMs,
            isVideoCall = session.isVideo,
            video = CallUi.Video(
                canSend = video.canSend,
                cameraOn = video.localCameraOn,
                sending = video.announcedCameraOn,
                peerCameraOn = video.remoteCameraOn,
                frontCamera = video.facing == CameraFacing.FRONT,
            ),
        )
    }

    /** iOS `InCallView.statusText`. */
    fun endedAs(reason: CallEndReason): CallUi.EndedAs = when (reason) {
        is CallEndReason.Hangup -> when (reason.reason) {
            HangupReason.HANGUP_REASON_DECLINED -> CallUi.EndedAs.DECLINED
            HangupReason.HANGUP_REASON_BUSY -> CallUi.EndedAs.BUSY
            HangupReason.HANGUP_REASON_TIMEOUT -> CallUi.EndedAs.MISSED
            else -> CallUi.EndedAs.ENDED
        }
        is CallEndReason.Error, is CallEndReason.Local -> CallUi.EndedAs.FAILED
    }

    fun startError(error: CallError): CallStartError = when (error) {
        CallError.NOT_A_CONTACT -> CallStartError.NOT_A_CONTACT
        CallError.BUSY -> CallStartError.BUSY
        CallError.SETUP_FAILED -> CallStartError.SETUP_FAILED
        CallError.CAMERA_DENIED -> CallStartError.CAMERA_DENIED
    }
}
