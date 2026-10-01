package com.construct.messenger.calls

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.channels.Channel
import shared.proto.signaling.v1.SignalingServiceOuterClass.InitiateCallResponse
import shared.proto.signaling.v1.SignalingServiceOuterClass.SignalErrorCode
import shared.proto.signaling.v1.SignalingServiceOuterClass.SignalRequest
import shared.proto.signaling.v1.SignalingServiceOuterClass.SignalResponse
import shared.proto.signaling.v1.Webrtc.HangupReason
import shared.proto.signaling.v1.Webrtc.IceCandidate
import shared.proto.signaling.v1.Webrtc.TurnCredentials
import shared.proto.signaling.v1.Webrtc.WebRTCSignal

/** **Canon:** iOS `CallTypes.swift` — `CallSession`, `CallState`, `CallEndReason`, `CallQuality`. */
data class CallSession(
    val id: String,
    val peerUserId: String,
    val peerName: String,
    val direction: Direction,
) {
    enum class Direction { INCOMING, OUTGOING }

    val isIncoming: Boolean get() = direction == Direction.INCOMING
}

sealed interface CallEndReason {
    data class Hangup(val reason: HangupReason) : CallEndReason
    data class Error(val code: SignalErrorCode) : CallEndReason
    data class Local(val what: String) : CallEndReason
}

sealed interface CallState {
    data object Idle : CallState
    data class Incoming(val session: CallSession) : CallState
    data class Dialing(val session: CallSession) : CallState
    data class Ringing(val session: CallSession) : CallState
    data class Connecting(val session: CallSession) : CallState
    data class Active(val session: CallSession) : CallState
    data class Ended(val session: CallSession, val reason: CallEndReason) : CallState
}

/** Coarse health from ICE: `RECONNECTING` while it reports `disconnected`. */
enum class CallQuality { GOOD, RECONNECTING }

/** Why an outgoing call did not start, for the UI to say. iOS `call_error_*`. */
enum class CallError { NOT_A_CONTACT, BUSY, SETUP_FAILED }

/** One ICE candidate as WebRTC hands it over: the candidate line in the clear. */
data class CallIce(val sdp: String, val sdpMid: String, val sdpMLineIndex: Int)

/**
 * The media of one call — a peer connection with one audio track. **Canon:** iOS
 * `WebRTCSessionProtocol`. C2 step 4 implements it on webrtc-sdk; the call machine never touches
 * WebRTC itself, so every decision above it is testable without a device.
 */
interface CallMedia {
    suspend fun createOffer(): String
    suspend fun setRemoteOffer(sdp: String)
    suspend fun createAnswer(): String
    suspend fun setRemoteAnswer(sdp: String)
    suspend fun addRemoteCandidate(candidate: CallIce)
    /** A new offer with fresh ICE credentials, on the same connection. */
    suspend fun restartIce(): String
    fun setMuted(muted: Boolean)
    fun close()

    /** Called from WebRTC's own threads; the machine moves each onto its own. */
    interface Listener {
        fun onLocalCandidate(candidate: CallIce)
        fun onConnected()
        fun onFailed()
        fun onQuality(quality: CallQuality)
    }

    enum class Role { CALLER, CALLEE }

    fun interface Factory {
        /** TURN when the server gave credentials, otherwise the STUN fallback. */
        fun create(role: Role, turn: TurnCredentials?, listener: Listener): CallMedia
    }
}

/** What the machine needs from [CallSignalTransport] — an interface so a test can stand in for the core. */
interface CallSignalPort {
    suspend fun send(peerAccountId: String, signal: WebRTCSignal): Boolean
    suspend fun sealCandidate(peerAccountId: String, sdpMid: String, sdpMLineIndex: Int, candidate: String): IceCandidate
    suspend fun openCandidate(deviceId: String, ice: IceCandidate): String?
    /** The device of [peerAccountId] call signals go to — what a candidate from them opens on. */
    suspend fun peerDevice(peerAccountId: String): String?
}

/** What the machine needs from [SignalingClient]. */
interface CallSignalingPort {
    suspend fun initiateCall(callId: String, calleeUserId: String, callerName: String): InitiateCallResponse
    suspend fun turnCredentials(callId: String?): TurnCredentials
    fun stream(outbound: Channel<SignalRequest>): Flow<SignalResponse>
}

/** Who we are and who they are, from the account and the contact list. */
interface CallPeers {
    fun myUserId(): String?
    fun myDeviceId(): String?
    suspend fun isCallable(userId: String): Boolean
    suspend fun name(userId: String): String
}
