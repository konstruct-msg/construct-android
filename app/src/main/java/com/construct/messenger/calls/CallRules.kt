package com.construct.messenger.calls

import shared.proto.signaling.v1.Webrtc.HangupReason
import uniffi.construct_core.callLocalHangupReason

/**
 * Every decision the call machine makes that does not need a call to make it. **Canon:** iOS
 * `CallTypes.swift` — each function there was split out of `CallManager` after a defect that no
 * test could reach while it was a chain of `if let active` inside a singleton; the names and the
 * cases are kept so the two can be read side by side.
 *
 * Not ported, because Android has no carrier for them: `incomingPushDisposition`,
 * `shouldIgnoreSilentPush`, `incomingCallFetchDisposition` (there is no VoIP push — the offer on
 * our own `MessageStream` is how a call arrives), and `outgoingCallSessionDisposition` /
 * `callSignalEncryptDisposition` (they wait out a SESSION_RESET_INIT, which no longer exists:
 * sending opens a session, see `docs/SESSIONS.md`).
 *
 * Moved to the core (`construct-rules` `calls`, todo 295) and called from [CallManager] directly:
 * what an arriving offer means (`callOnOffer`), what the stream closing means
 * (`callOnStreamClosed`), where a hangup goes (`callHangupRoute`), and glare inside the first.
 * The offer rule's `RESUME_ANSWER` is not reachable here: an incoming call starts only from its
 * offer, so it cannot be answered before the offer exists; it matters the day a carrier without
 * SDP (UnifiedPush) rings first.
 */
object CallRules {

    /**
     * The one reading of "we hold an offer". iOS build 613 asked it two ways — `!= nil` and
     * `!isEmpty` — and an empty string answered both: candidates buffered against an offer that
     * the answer then found absent.
     */
    fun offerSdpIsUsable(sdp: String?): Boolean = !sdp.isNullOrEmpty()

    /**
     * Remote candidates wait while an offer is held (nothing has a remote description to check
     * them against) or while there is no media yet to give them to. iOS buffers on the first only
     * and hands the rest to a `webrtc?` that may be nil — a candidate lost in silence.
     */
    fun bufferRemoteCandidate(holdsOffer: Boolean, hasMedia: Boolean): Boolean = holdsOffer || !hasMedia

    /** Before media: [CallTiming.MAX_STREAM_RETRIES]; after: [CallTiming.MAX_POST_MEDIA_RECONNECTS]. */
    fun canReconnectStream(mediaConnected: Boolean, streamRetries: Int, postMediaReconnects: Int): Boolean =
        if (mediaConnected) postMediaReconnects < CallTiming.MAX_POST_MEDIA_RECONNECTS else streamRetries < CallTiming.MAX_STREAM_RETRIES

    /** The user asked to call someone. */
    enum class OutgoingRequest {
        START,
        /** The same peer is ringing us: answer that instead of starting a competing call. */
        ANSWER_INSTEAD,
        BUSY,
    }

    fun outgoingRequest(hasActiveCall: Boolean, activeIsIncomingFromPeer: Boolean): OutgoingRequest = when {
        !hasActiveCall -> OutgoingRequest.START
        activeIsIncomingFromPeer -> OutgoingRequest.ANSWER_INSTEAD
        else -> OutgoingRequest.BUSY
    }

    enum class IceRestartDisposition {
        SCHEDULE,
        /** The callee waits for the caller's restart offer: two restarts would cross. */
        CALLEE_WAITS,
        SKIP,
    }

    /** After ICE reports `disconnected`. Only the caller restarts, once media has been up, at most three times. */
    fun iceRestartDisposition(mediaConnected: Boolean, isCaller: Boolean, restartPending: Boolean, attempts: Int): IceRestartDisposition = when {
        !mediaConnected -> IceRestartDisposition.SKIP
        !isCaller -> IceRestartDisposition.CALLEE_WAITS
        restartPending || attempts >= CallTiming.MAX_ICE_RESTARTS -> IceRestartDisposition.SKIP
        else -> IceRestartDisposition.SCHEDULE
    }

    /** A call ended by our side: declining one that is still ringing, otherwise a normal hangup. */
    fun localHangupReason(isIncoming: Boolean, stillRinging: Boolean): HangupReason =
        CallRuleWire.proto(callLocalHangupReason(CallRuleWire.direction(isIncoming), stillRinging))
}

/** **Canon:** iOS `NetworkTiming.Calls` and the constants in `CallManager`. */
object CallTiming {
    /** An ended call id is remembered only to outlive its own signals still in flight. */
    const val ENDED_CALL_MEMORY_MS = 120_000L
    const val ICE_RESTART_GRACE_MS = 2_000L
    const val MAX_ICE_RESTARTS = 3
    const val MAX_STREAM_RETRIES = 3
    const val MAX_POST_MEDIA_RECONNECTS = 5
    const val ENDED_AUTO_CLEAR_MS = 3_000L
    /** Local candidates coalesced before a flush — under the server's 10 signals a second. */
    const val ICE_FLUSH_MS = 200L

    /**
     * Android only. An unanswered incoming call ends after the server's registry keeps the call
     * (90 s). iOS leaves this to CallKit's screen; here nothing else would ever end it, and an
     * incoming call nobody ends makes every later caller busy.
     */
    const val RING_TIMEOUT_MS = 90_000L

    /** How long a closed call's stream lives on so the hangup written to it leaves. */
    const val STREAM_LINGER_MS = 2_000L
}

/** Call ids that ended, with when. iOS `endedCallIds` / `hasRecentlyEnded`. */
class EndedCalls(private val nowMs: () -> Long) {
    private val ended = HashMap<String, Long>()

    fun remember(callId: String) {
        val now = nowMs()
        ended[callId] = now
        ended.entries.removeAll { now - it.value >= CallTiming.ENDED_CALL_MEMORY_MS }
    }

    fun contains(callId: String): Boolean = ended[callId]?.let { nowMs() - it < CallTiming.ENDED_CALL_MEMORY_MS } == true
}
