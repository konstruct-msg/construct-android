package com.construct.messenger.calls

import shared.proto.signaling.v1.Webrtc.HangupReason

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
 * Ported but not reachable from [CallManager] yet: [OfferDisposition.RESUME_ANSWER] and the
 * offer wait it implies. An incoming call starts here only from its offer, so it cannot be
 * answered before the offer exists; they matter again the day a carrier without SDP (UnifiedPush)
 * rings first.
 */
object CallRules {

    /** What to do with an SDP offer for a call id we are not negotiating. iOS `CallOfferDisposition`. */
    enum class OfferDisposition {
        /** The call is over; its offer outlived it. Do not ring for it. */
        IGNORE_ENDED,
        /** The user answered before the SDP came. Attach it and finish the answer. */
        RESUME_ANSWER,
        /** Ringing, unanswered: attach the SDP for the answer to use. */
        STORE_FOR_ANSWER,
        /** No call has this id. */
        NEW_CALL,
    }

    /**
     * "There is a call" and "here is its SDP" can arrive in either order, and on 2026-08-05 the
     * unhandled orders cost a call each way on iOS: an offer 30 s after the answer that nothing
     * consumed, and one 8 s after the end that rang again for a call nobody was making.
     */
    fun offerDisposition(hasRecentlyEnded: Boolean, matchesActiveIncomingCall: Boolean, awaitingOfferAfterAnswer: Boolean): OfferDisposition =
        when {
            hasRecentlyEnded -> OfferDisposition.IGNORE_ENDED
            !matchesActiveIncomingCall -> OfferDisposition.NEW_CALL
            awaitingOfferAfterAnswer -> OfferDisposition.RESUME_ANSWER
            else -> OfferDisposition.STORE_FOR_ANSWER
        }

    /** An offer for a call we already hold. iOS `RemoteOfferDisposition`. */
    enum class RemoteOfferDisposition {
        /** Under way — the peer renegotiating (ICE restart). Apply it; asking again would drop the call. */
        RENEGOTIATE,
        /** Incoming and unanswered. Hold the SDP: consent is what starts negotiation. */
        HOLD_UNTIL_ANSWERED,
    }

    /**
     * iOS build 583: the offer for a ringing call was negotiated and answered before the user
     * touched anything, and the real answer then waited 45 s for an offer already used.
     */
    fun remoteOfferDisposition(isIncomingCall: Boolean, hasAnswered: Boolean): RemoteOfferDisposition =
        if (isIncomingCall && !hasAnswered) RemoteOfferDisposition.HOLD_UNTIL_ANSWERED else RemoteOfferDisposition.RENEGOTIATE

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

    /** What closing the signalling stream means. iOS `SignalingStreamClosedDisposition`. */
    enum class StreamClosedDisposition {
        RECONNECT,
        /** No more reconnects, but something other than the stream bounds the call. */
        KEEP_ON_MESSAGE_PATH,
        /** The stream was the last thing that could end this call. */
        END_CALL,
    }

    /**
     * The stream is not the call — offer, answer and hangup ride the message path — but it is the
     * only path for the server's own hangup, so a call may outlive it only while something else
     * bounds it: media up, or (iOS) the 45 s wait for an offer after the answer.
     */
    fun streamClosedDisposition(mediaConnected: Boolean, awaitingOfferAfterAnswer: Boolean, canReconnect: Boolean): StreamClosedDisposition =
        when {
            canReconnect -> StreamClosedDisposition.RECONNECT
            mediaConnected || awaitingOfferAfterAnswer -> StreamClosedDisposition.KEEP_ON_MESSAGE_PATH
            else -> StreamClosedDisposition.END_CALL
        }

    /** Before media: [CallTiming.MAX_STREAM_RETRIES]; after: [CallTiming.MAX_POST_MEDIA_RECONNECTS]. */
    fun canReconnectStream(mediaConnected: Boolean, streamRetries: Int, postMediaReconnects: Int): Boolean =
        if (mediaConnected) postMediaReconnects < CallTiming.MAX_POST_MEDIA_RECONNECTS else streamRetries < CallTiming.MAX_STREAM_RETRIES

    enum class HangupOrigin { LOCAL, REMOTE }

    enum class HangupChannel {
        /** `WebRTCSignal.hangup` by the Double Ratchet — the peer. */
        E2EE,
        /** The stream's hangup — the server's occupancy table. */
        SIGNALING_STREAM,
    }

    /**
     * iOS AC92380B: a received hangup that skipped the stream left the receiver "in a call" on the
     * server, and the peer's call-back four seconds later was refused as busy. The peer already
     * knows, so a remote hangup is not encrypted back.
     */
    fun hangupChannels(origin: HangupOrigin): List<HangupChannel> = when (origin) {
        HangupOrigin.LOCAL -> listOf(HangupChannel.E2EE, HangupChannel.SIGNALING_STREAM)
        HangupOrigin.REMOTE -> listOf(HangupChannel.SIGNALING_STREAM)
    }

    enum class Glare { KEEP_OURS, YIELD }

    /**
     * Both sides dialled each other at once, each with its own call id. The larger user id keeps
     * its outgoing call and ignores the offer; the smaller one answers it. Compared as iOS
     * compares them (`myUserId > callerUserId`, the ids as the server gave them): if the two
     * platforms ordered differently, both would yield, or both would keep.
     */
    fun glare(myUserId: String, peerUserId: String): Glare = if (myUserId > peerUserId) Glare.KEEP_OURS else Glare.YIELD

    /** A new call id arrived while another call is held. */
    enum class NewCallDisposition {
        /** Nothing is held. */
        RING,
        /** Glare, and we keep ours. */
        IGNORE_GLARE,
        /** Glare, and we yield: our outgoing call is replaced by theirs. */
        REPLACE_OUTGOING,
        /** Another call is up. Refuse this one. */
        BUSY,
    }

    /**
     * iOS decides "busy" on the VoIP push, and the offer that follows replaces whatever call was
     * up. Android has no push: the offer is the only carrier, so busy is decided here — and the
     * call the user is in is never the one that goes.
     */
    fun newCallDisposition(hasActiveCall: Boolean, activeIsOutgoingToCaller: Boolean, glare: Glare?): NewCallDisposition = when {
        !hasActiveCall -> NewCallDisposition.RING
        activeIsOutgoingToCaller && glare == Glare.KEEP_OURS -> NewCallDisposition.IGNORE_GLARE
        activeIsOutgoingToCaller -> NewCallDisposition.REPLACE_OUTGOING
        else -> NewCallDisposition.BUSY
    }

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
        if (isIncoming && stillRinging) {
            HangupReason.HANGUP_REASON_DECLINED
        } else {
            HangupReason.HANGUP_REASON_NORMAL
        }
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
