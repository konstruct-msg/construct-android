package com.construct.messenger.calls

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
 * Moved to the core (`construct-rules` `calls` and `call_machine`, todo 295) and called from
 * [CallManager] through its `CallMachine`: the call's phase, devices, answer, held offer, media,
 * the stream and ICE-restart budgets, ended ids and the deadlines (unanswered, offer wait, ICE
 * grace); what an arriving offer means, glare, the stream closing, where a hangup goes. The
 * machine's `RESUME_ANSWER` and offer wait are not reachable here: an incoming call starts only
 * from its offer, so it cannot be answered before the offer exists; they matter the day a carrier
 * without SDP (UnifiedPush) rings first.
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
}

/**
 * This platform's own call timings. The unanswered-call, offer-wait, ICE-restart and stream-budget
 * timings, and the ended-id memory, are the core's call machine's (`callTimings()`), shared with iOS.
 */
object CallTiming {
    const val ENDED_AUTO_CLEAR_MS = 3_000L
    /** Local candidates coalesced before a flush — under the server's 10 signals a second. */
    const val ICE_FLUSH_MS = 200L

    /** How long a closed call's stream lives on so the hangup written to it leaves. */
    const val STREAM_LINGER_MS = 2_000L
}
