package com.construct.messenger.calls

import com.construct.messenger.diagnostics.Log
import io.grpc.Status
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import shared.proto.signaling.v1.SignalingServiceOuterClass.SignalErrorCode
import shared.proto.signaling.v1.SignalingServiceOuterClass.SignalRequest
import shared.proto.signaling.v1.SignalingServiceOuterClass.SignalResponse
import shared.proto.signaling.v1.Webrtc.CallAnswer
import shared.proto.signaling.v1.Webrtc.CallOffer
import shared.proto.signaling.v1.Webrtc.CallType
import shared.proto.signaling.v1.Webrtc.HangupReason
import shared.proto.signaling.v1.Webrtc.IceCandidate
import shared.proto.signaling.v1.Webrtc.TurnCredentials
import shared.proto.signaling.v1.Webrtc.WebRTCSignal

/**
 * One call at a time: its state, its signals, its timers. **Canon:** iOS `CallManager` — the
 * same transitions, the same guards, the same names where Kotlin allows.
 *
 * Everything runs on one thread ([scope] is confined to it), as iOS runs on the main actor: no
 * two handlers interleave except at a suspension, and after every suspension a handler checks
 * that the call it started with is still [active] — a hangup or a glare may have replaced it.
 *
 * Signals in come from [CallSignalInbox], collected from [start] at app start: an offer that
 * arrives before anyone listens is gone (a `SharedFlow` keeps nothing for a late collector).
 * Signals out go through one queue per call, so the server gets them in the order they were
 * made — offer, candidates, hangup.
 *
 * Where Android differs, it is because a carrier iOS has does not exist here — there is no VoIP
 * push, so the offer is how a call arrives and busy is decided on it ([CallRules.newCallDisposition]),
 * and an unanswered call ends after [CallTiming.RING_TIMEOUT_MS] because no CallKit screen does it.
 */
@Singleton
class CallManager internal constructor(
    private val signals: CallSignalPort,
    private val signaling: CallSignalingPort,
    private val peers: CallPeers,
    private val mediaFactory: CallMedia.Factory,
    private val inbox: CallSignalInbox,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Inject
    constructor(
        signals: CallSignalPort,
        signaling: CallSignalingPort,
        peers: CallPeers,
        mediaFactory: CallMedia.Factory,
        inbox: CallSignalInbox,
    ) : this(
        signals, signaling, peers, mediaFactory, inbox,
        CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1)),
        System::currentTimeMillis,
    )

    private val _state = MutableStateFlow<CallState>(CallState.Idle)
    val state: StateFlow<CallState> = _state.asStateFlow()

    private val _quality = MutableStateFlow(CallQuality.GOOD)
    val quality: StateFlow<CallQuality> = _quality.asStateFlow()

    private val _lastError = MutableStateFlow<CallError?>(null)
    val lastError: StateFlow<CallError?> = _lastError.asStateFlow()

    private var active: ActiveCall? = null
    private val ended = EndedCalls(nowMs)
    private var started = false

    /** Subscribe to incoming signals. Once, from `Application.onCreate` — before anything can deliver one. */
    fun start() {
        if (started) return
        started = true
        scope.launch { inbox.signals.collect { onSignal(it) } }
    }

    // ── What the user does ──────────────────────────────────────────────────

    fun startOutgoingCall(peerUserId: String) {
        scope.launch { startOutgoing(peerUserId) }
    }

    fun answer() {
        scope.launch {
            val call = active ?: return@launch
            if (!call.session.isIncoming || _state.value !is CallState.Incoming) return@launch
            answer(call)
        }
    }

    /** Hang up, or decline a call still ringing. */
    fun end() {
        scope.launch {
            val call = active ?: return@launch
            val reason = CallRules.localHangupReason(call.session.isIncoming, _state.value is CallState.Incoming)
            Log.i(TAG, "end requested reason=$reason ${describe(call)}")
            endLocally(call, reason)
        }
    }

    fun setMuted(muted: Boolean) {
        scope.launch { active?.media?.setMuted(muted) }
    }

    fun clearLastError() {
        _lastError.value = null
    }

    /** Leave the "ended" screen before the auto-clear does. */
    fun dismissEnded() {
        if (_state.value is CallState.Ended) _state.value = CallState.Idle
    }

    // ── Outgoing ────────────────────────────────────────────────────────────

    private suspend fun startOutgoing(peerUserId: String) {
        if (!peers.isCallable(peerUserId)) {
            Log.i(TAG, "SECURITY[call_gate]: outgoing call to ${peerUserId.take(8)}… — not a contact")
            _lastError.value = CallError.NOT_A_CONTACT
            return
        }
        val current = active
        when (CallRules.outgoingRequest(current != null, current?.session?.let { it.isIncoming && it.peerUserId == peerUserId } == true)) {
            CallRules.OutgoingRequest.ANSWER_INSTEAD -> {
                Log.i(TAG, "glare: calling ${peerUserId.take(8)}… while they call us — answering instead")
                if (_state.value is CallState.Incoming) answer(current!!)
                return
            }
            CallRules.OutgoingRequest.BUSY -> {
                Log.i(TAG, "busy — not calling ${peerUserId.take(8)}…")
                _lastError.value = CallError.BUSY
                return
            }
            CallRules.OutgoingRequest.START -> Unit
        }

        val session = CallSession(UUID.randomUUID().toString(), peerUserId, peers.name(peerUserId), CallSession.Direction.OUTGOING)
        val call = begin(session, CallState.Dialing(session))
        try {
            // The caller's name stays empty: the callee names us from its own contacts (iOS does
            // the same with the name the push carries), and the server needs no name to route.
            val init = signaling.initiateCall(session.id, peerUserId, callerName = "")
            if (active !== call) return
            Log.i(TAG, "InitiateCall: calleeOnline=${init.calleeOnline} call=${session.id.take(8)}…")
            call.turn = fetchTurn(session.id)
            if (active !== call) return
            openStream(call)
            val media = ensureMedia(call, CallMedia.Role.CALLER)
            val sdp = media.createOffer()
            if (active !== call) return
            if (!sendOffer(call, sdp, isIceRestart = false)) throw IllegalStateException("offer not sent")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "outgoing call setup failed: ${e.message}")
            val status = Status.fromThrowable(e)
            _lastError.value = when {
                status.code == Status.Code.PERMISSION_DENIED -> CallError.NOT_A_CONTACT
                status.description?.contains("busy", ignoreCase = true) == true -> CallError.BUSY
                else -> CallError.SETUP_FAILED
            }
            // InitiateCall may already have made the callee ring; tell them and the server.
            if (active !== call) return
            openStream(call)
            sendHangup(call, HangupReason.HANGUP_REASON_NORMAL, CallRules.HangupOrigin.LOCAL)
            endActive(CallEndReason.Local("Call setup failed"))
        }
    }

    /**
     * TURN, three tries, then STUN only — which on a mobile or symmetric NAT rarely connects, so a
     * transient failure is worth retrying. A rate limit is not: retrying spends the budget faster.
     */
    private suspend fun fetchTurn(callId: String): TurnCredentials? {
        for (attempt in 1..3) {
            try {
                val turn = signaling.turnCredentials(callId)
                if (turn.urlsCount > 0) return turn
                Log.i(TAG, "TURN $attempt/3: no urls")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "TURN $attempt/3 failed: ${e.message}")
                if (Status.fromThrowable(e).code == Status.Code.RESOURCE_EXHAUSTED) break
            }
            if (attempt < 3) delay(400L * attempt)
        }
        Log.i(TAG, "TURN unavailable — STUN only (call=${callId.take(8)}…)")
        return null
    }

    /** Every offer leaves here. An empty one is refused: the far side would wait 45 s on it. */
    private suspend fun sendOffer(call: ActiveCall, sdp: String, isIceRestart: Boolean): Boolean {
        if (!CallRules.offerSdpIsUsable(sdp)) {
            Log.w(TAG, "refusing an offer with no SDP (iceRestart=$isIceRestart) ${describe(call)}")
            return false
        }
        val offer = CallOffer.newBuilder()
            .setSdp(sdp)
            .setCallType(CallType.CALL_TYPE_AUDIO)
            .setCallerDeviceId(myDevice())
            .setCallerUserId(peers.myUserId().orEmpty())
            .setOfferedAt(nowMs())
        val sent = sendE2ee(call, signal(call).setOffer(offer).build()).await()
        Log.i(TAG, "${if (isIceRestart) "ICE restart offer" else "offer"} ${if (sent) "sent" else "NOT sent"} sdp=${sdp.length}b ${describe(call)}")
        return sent
    }

    // ── Incoming ────────────────────────────────────────────────────────────

    private suspend fun onSignal(incoming: CallSignalInbox.Incoming) {
        val signal = incoming.signal
        val call = active?.takeIf { it.session.id == signal.callId }
        when (signal.signalCase) {
            WebRTCSignal.SignalCase.OFFER -> {
                if (call != null) {
                    call.peerDevice = incoming.deviceId
                    when (CallRules.remoteOfferDisposition(call.session.isIncoming, call.answeredAtMs != null)) {
                        CallRules.RemoteOfferDisposition.HOLD_UNTIL_ANSWERED -> holdOffer(call, signal.offer.sdp)
                        CallRules.RemoteOfferDisposition.RENEGOTIATE -> renegotiate(call, signal.offer.sdp)
                    }
                } else {
                    incomingOffer(signal.callId, incoming.accountId, incoming.deviceId, signal.offer.sdp)
                }
            }
            WebRTCSignal.SignalCase.ANSWER -> if (call != null) remoteAnswer(call, signal.answer.sdp)
            WebRTCSignal.SignalCase.ICE_CANDIDATE -> if (call != null) remoteCandidates(call, incoming.deviceId, listOf(signal.iceCandidate))
            WebRTCSignal.SignalCase.ICE_CANDIDATES -> if (call != null) remoteCandidates(call, incoming.deviceId, signal.iceCandidates.candidatesList)
            WebRTCSignal.SignalCase.HANGUP -> if (call != null) {
                // The peer knows; the server does not until our stream says so (AC92380B).
                sendHangup(call, signal.hangup.reason, CallRules.HangupOrigin.REMOTE)
                endActive(CallEndReason.Hangup(signal.hangup.reason))
            }
            WebRTCSignal.SignalCase.BUSY -> if (call != null) {
                sendHangup(call, HangupReason.HANGUP_REASON_BUSY, CallRules.HangupOrigin.REMOTE)
                endActive(CallEndReason.Hangup(HangupReason.HANGUP_REASON_BUSY))
            }
            WebRTCSignal.SignalCase.RINGING -> if (call != null && _state.value is CallState.Dialing) _state.value = CallState.Ringing(call.session)
            else -> Unit
        }
        if (call == null && signal.signalCase != WebRTCSignal.SignalCase.OFFER) {
            Log.i(TAG, "signal ${signal.signalCase} for no current call (${signal.callId.take(8)}…) — dropped")
        }
    }

    private suspend fun incomingOffer(callId: String, callerId: String, callerDevice: String, sdp: String) {
        // Refused, not stored: an offer that cannot be negotiated would ring for a call that cannot connect.
        if (!CallRules.offerSdpIsUsable(sdp)) {
            Log.w(TAG, "offer from ${callerId.take(8)}… carries no SDP — not ringing (call=${callId.take(8)}…)")
            return
        }
        // No call with this id is held (the caller checked); the dispositions that need one are
        // reached through holdOffer. What is left: ended, or new.
        when (CallRules.offerDisposition(ended.contains(callId), matchesActiveIncomingCall = false, awaitingOfferAfterAnswer = false)) {
            CallRules.OfferDisposition.IGNORE_ENDED -> {
                Log.i(TAG, "offer for ended call ${callId.take(8)}… — not ringing again")
                return
            }
            else -> Unit
        }
        val current = active
        val outgoingToCaller = current != null && !current.session.isIncoming && current.session.peerUserId == callerId
        val glare = if (outgoingToCaller) CallRules.glare(peers.myUserId().orEmpty(), callerId) else null
        when (CallRules.newCallDisposition(current != null, outgoingToCaller, glare)) {
            CallRules.NewCallDisposition.IGNORE_GLARE -> {
                Log.i(TAG, "glare: keeping our call to ${callerId.take(8)}… — ignoring theirs")
                return
            }
            CallRules.NewCallDisposition.BUSY -> {
                Log.i(TAG, "busy — refusing call ${callId.take(8)}… from ${callerId.take(8)}…")
                ended.remember(callId)
                scope.launch { signals.send(callerId, CallSignalWire.hangup(callId, myDevice(), HangupReason.HANGUP_REASON_BUSY, nowMs())) }
                return
            }
            CallRules.NewCallDisposition.REPLACE_OUTGOING -> Log.i(TAG, "glare: yielding our call to ${callerId.take(8)}… — taking theirs")
            CallRules.NewCallDisposition.RING -> Unit
        }
        val session = CallSession(callId, callerId, peers.name(callerId), CallSession.Direction.INCOMING)
        val call = begin(session, CallState.Incoming(session))
        call.peerDevice = callerDevice
        call.pendingOfferSdp = sdp
        call.ringTimeout = scope.launch {
            delay(CallTiming.RING_TIMEOUT_MS)
            if (active === call && _state.value is CallState.Incoming) {
                Log.i(TAG, "unanswered for ${CallTiming.RING_TIMEOUT_MS / 1000}s ${describe(call)}")
                endLocally(call, HangupReason.HANGUP_REASON_TIMEOUT)
            }
        }
        Log.i(TAG, "incoming call ${callId.take(8)}… from ${callerId.take(8)}… sdp=${sdp.length}b")
    }

    /**
     * Another offer for a call that is ringing here — the caller re-offered before we answered.
     * It replaces the held one; consent, not the offer, starts negotiation.
     */
    private fun holdOffer(call: ActiveCall, sdp: String) {
        if (!CallRules.offerSdpIsUsable(sdp)) {
            Log.w(TAG, "held offer carries no SDP — ending rather than waiting on it ${describe(call)}")
            endActive(CallEndReason.Local("Offer handling failed"))
            return
        }
        call.pendingOfferSdp = sdp
        Log.i(TAG, "holding the offer until the user answers ${describe(call)}")
    }

    /**
     * The user answered. The offer is always held by now: on Android a call starts ringing only
     * from its offer (there is no VoIP push to outrun it), so iOS's "answered before the offer"
     * wait has no order that reaches it here.
     */
    private suspend fun answer(call: ActiveCall) {
        call.ringTimeout?.cancel()
        _state.value = CallState.Connecting(call.session)
        try {
            call.turn = fetchTurn(call.session.id)
            if (active !== call) return
            ensureMedia(call, CallMedia.Role.CALLEE)
            val held = call.pendingOfferSdp?.takeIf(CallRules::offerSdpIsUsable) ?: error("no offer held")
            applyOfferAndAnswer(call, held)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "accept failed: ${e.message}")
            if (active === call) endActive(CallEndReason.Local("Accept failed"))
        }
    }

    /** Apply the held offer and answer it — with the candidates that waited for it in between. */
    private suspend fun applyOfferAndAnswer(call: ActiveCall, sdp: String) {
        val media = call.media ?: error("no media after ensureMedia")
        media.setRemoteOffer(sdp)
        call.pendingOfferSdp = null
        drainCandidates(call)
        val answer = media.createAnswer()
        check(answer.isNotEmpty()) { "createAnswer returned no SDP" }
        if (active !== call) return
        sendAnswer(call, answer)
        call.answeredAtMs = nowMs()
        _state.value = CallState.Active(call.session, call.answeredAtMs ?: nowMs())
        // The stream carries our presence; without a ringing on it the server reaps the call
        // as callee-offline seconds after media is up.
        openStream(call)
        sendStream(call, CallSignalWire.ringing(call.session.id, myDevice(), nowMs()))
    }

    /** An offer for a call under way: the caller restarting ICE. */
    private suspend fun renegotiate(call: ActiveCall, sdp: String) {
        try {
            check(CallRules.offerSdpIsUsable(sdp)) { "remote offer carries no SDP" }
            val media = ensureMedia(call, if (call.session.isIncoming) CallMedia.Role.CALLEE else CallMedia.Role.CALLER)
            media.setRemoteOffer(sdp)
            val answer = media.createAnswer()
            check(answer.isNotEmpty()) { "createAnswer returned no SDP" }
            if (active !== call) return
            sendAnswer(call, answer)
            // A renegotiation does not re-stamp the answer: duration is counted from the first.
            if (call.answeredAtMs == null) call.answeredAtMs = nowMs()
            _state.value = CallState.Active(call.session, call.answeredAtMs ?: nowMs())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "offer handling failed: ${e.message}")
            if (active === call) endActive(CallEndReason.Local("Offer handling failed"))
        }
    }

    private suspend fun remoteAnswer(call: ActiveCall, sdp: String) {
        val media = call.media ?: return
        try {
            media.setRemoteAnswer(sdp)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "remote answer not applied: ${e.message}")
            return
        }
        if (active !== call) return
        // Only the first answer is the call being answered; a later one answers an ICE restart.
        if (call.answeredAtMs == null) call.answeredAtMs = nowMs()
        _state.value = CallState.Active(call.session, call.answeredAtMs ?: nowMs())
        Log.i(TAG, "answer applied ${describe(call)}")
    }

    private suspend fun remoteCandidates(call: ActiveCall, deviceId: String, candidates: List<IceCandidate>) {
        for (ice in candidates) {
            val line = signals.openCandidate(deviceId, ice)
            if (line == null) {
                Log.w(TAG, "candidate from ${deviceId.take(8)}… did not open — dropped")
                continue
            }
            if (active !== call) return
            val candidate = CallIce(line, ice.sdpMid, ice.sdpMLineIndex)
            val media = call.media
            if (media == null || CallRules.bufferRemoteCandidate(CallRules.offerSdpIsUsable(call.pendingOfferSdp), hasMedia = true)) {
                call.pendingRemoteCandidates += candidate
            } else {
                runCatching { media.addRemoteCandidate(candidate) }.onFailure { Log.w(TAG, "candidate not added: ${it.message}") }
            }
        }
    }

    private suspend fun drainCandidates(call: ActiveCall) {
        val media = call.media ?: return
        val buffered = call.pendingRemoteCandidates.toList()
        call.pendingRemoteCandidates.clear()
        if (buffered.isNotEmpty()) Log.i(TAG, "applying ${buffered.size} buffered candidate(s)")
        buffered.forEach { c -> runCatching { media.addRemoteCandidate(c) }.onFailure { Log.w(TAG, "candidate not added: ${it.message}") } }
    }

    private fun sendAnswer(call: ActiveCall, sdp: String) {
        val answer = CallAnswer.newBuilder()
            .setSdp(sdp)
            .setAnswererDeviceId(myDevice())
            .setAnswererUserId(peers.myUserId().orEmpty())
            .setAnsweredAt(nowMs())
        sendE2ee(call, signal(call).setAnswer(answer).build())
        Log.i(TAG, "answer queued ${describe(call)}")
    }

    // ── Media ───────────────────────────────────────────────────────────────

    private fun ensureMedia(call: ActiveCall, role: CallMedia.Role): CallMedia {
        call.media?.let { return it }
        val media = mediaFactory.create(role, call.turn, listener(call))
        call.media = media
        _quality.value = CallQuality.GOOD
        // Candidates that came before there was anything to give them to.
        if (call.pendingOfferSdp == null) scope.launch { drainCandidates(call) }
        Log.i(TAG, "media created role=$role turn=${if (call.turn != null) "yes" else "STUN only"}")
        return media
    }

    private fun listener(call: ActiveCall) = object : CallMedia.Listener {
        override fun onLocalCandidate(candidate: CallIce) {
            scope.launch { if (active === call) queueLocalCandidate(call, candidate) }
        }

        override fun onConnected() {
            scope.launch {
                if (active !== call) return@launch
                // From here the call outlives its signalling stream.
                call.mediaConnected = true
                call.iceRestart?.cancel()
                call.iceRestart = null
                call.iceRestartInFlight = false
                call.iceRestartAttempts = 0
                // The server never sees the answer (it went by E2EE); this moves the call off the
                // ringing reaper onto the keepalive one.
                sendStream(call, CallSignalWire.connected(call.session.id, myDevice(), nowMs()))
                Log.i(TAG, "media connected ${describe(call)}")
            }
        }

        override fun onFailed() {
            scope.launch {
                if (active !== call) return@launch
                Log.w(TAG, "ICE failed — ending ${describe(call)}")
                endActive(CallEndReason.Local("ICE connection failed"))
            }
        }

        override fun onQuality(quality: CallQuality) {
            scope.launch {
                if (active !== call) return@launch
                _quality.value = quality
                when (quality) {
                    CallQuality.RECONNECTING -> scheduleIceRestart(call)
                    CallQuality.GOOD -> {
                        call.iceRestart?.cancel()
                        call.iceRestart = null
                    }
                }
            }
        }
    }

    /**
     * Local candidates go by E2EE — queued and persisted, so they arrive whichever side joined the
     * stream first (the stream relay buffers nothing). Coalesced for 200 ms, sealed one by one,
     * and split so no signal nears the core's padding cap.
     */
    private fun queueLocalCandidate(call: ActiveCall, candidate: CallIce) {
        call.pendingLocalCandidates += candidate
        if (call.iceFlush?.isActive == true) return
        call.iceFlush = scope.launch {
            delay(CallTiming.ICE_FLUSH_MS)
            if (active !== call) return@launch
            val batch = call.pendingLocalCandidates.toList()
            call.pendingLocalCandidates.clear()
            call.iceFlush = null
            if (batch.isEmpty()) return@launch
            enqueue(call) {
                val sealed = batch.map { signals.sealCandidate(call.session.peerUserId, it.sdpMid, it.sdpMLineIndex, it.sdp) }
                CallSignalWire.batches(sealed).map { CallSignalWire.iceSignal(call.session.id, myDevice(), it, nowMs()) }
            }
            Log.i(TAG, "flushing ${batch.size} candidate(s) ${describe(call)}")
        }
    }

    /** Only the caller restarts, 2 s after `disconnected` if it has not healed, at most three times. */
    private fun scheduleIceRestart(call: ActiveCall) {
        val decision = CallRules.iceRestartDisposition(
            mediaConnected = call.mediaConnected,
            isCaller = !call.session.isIncoming,
            restartPending = call.iceRestartInFlight || call.iceRestart != null,
            attempts = call.iceRestartAttempts,
        )
        when (decision) {
            CallRules.IceRestartDisposition.CALLEE_WAITS -> Log.i(TAG, "ICE disconnected — the caller restarts")
            CallRules.IceRestartDisposition.SKIP -> Log.i(TAG, "ICE disconnected — no restart (attempts=${call.iceRestartAttempts})")
            CallRules.IceRestartDisposition.SCHEDULE -> call.iceRestart = scope.launch {
                delay(CallTiming.ICE_RESTART_GRACE_MS)
                call.iceRestart = null
                if (active !== call || _quality.value != CallQuality.RECONNECTING) return@launch
                restartIce(call)
            }
        }
    }

    private suspend fun restartIce(call: ActiveCall) {
        val media = call.media ?: return
        call.iceRestartInFlight = true
        call.iceRestartAttempts += 1
        try {
            openStream(call)
            val sdp = media.restartIce()
            if (active === call) sendOffer(call, sdp, isIceRestart = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A failed restart is logged, not fatal: the call may still heal by itself.
            Log.w(TAG, "ICE restart failed: ${e.message}")
        } finally {
            if (active === call) call.iceRestartInFlight = false
        }
    }

    // ── The signalling stream ───────────────────────────────────────────────

    private fun openStream(call: ActiveCall) {
        if (call.outbound != null) return
        val outbound = Channel<SignalRequest>(Channel.UNLIMITED)
        call.outbound = outbound
        call.stream = scope.launch {
            try {
                signaling.stream(outbound).collect { onStream(call, it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "signalling stream failed: ${e.message}")
            }
            onStreamClosed(call, outbound)
        }
        Log.i(TAG, "signalling stream opening ${describe(call)}")
    }

    private fun onStream(call: ActiveCall, response: SignalResponse) {
        if (active !== call) return
        when (response.responseCase) {
            SignalResponse.ResponseCase.ERROR -> {
                Log.w(TAG, "signalling error ${response.error.code}: ${response.error.message}")
                // A dropped candidate burst — not the end of the call.
                if (response.error.code != SignalErrorCode.SIGNAL_ERROR_CODE_RATE_LIMITED) endActive(CallEndReason.Error(response.error.code))
            }
            SignalResponse.ResponseCase.SIGNAL -> {
                val signal = response.signal
                if (!CallSignalWire.admittedOnStream(signal)) {
                    Log.w(TAG, "SECURITY[call_gate]: refused ${signal.signalCase} on the signalling stream — SDP arrives E2EE only")
                    return
                }
                when (signal.signalCase) {
                    WebRTCSignal.SignalCase.RINGING ->
                        if (_state.value is CallState.Dialing) _state.value = CallState.Ringing(call.session)
                    WebRTCSignal.SignalCase.BUSY -> endActive(CallEndReason.Hangup(HangupReason.HANGUP_REASON_BUSY))
                    WebRTCSignal.SignalCase.HANGUP -> {
                        Log.i(TAG, "hangup on the stream reason=${signal.hangup.reason}")
                        endActive(CallEndReason.Hangup(signal.hangup.reason))
                    }
                    WebRTCSignal.SignalCase.ICE_CANDIDATE, WebRTCSignal.SignalCase.ICE_CANDIDATES -> scope.launch {
                        val device = call.peerDevice ?: signals.peerDevice(call.session.peerUserId)?.also { call.peerDevice = it } ?: return@launch
                        val list = if (signal.hasIceCandidate()) listOf(signal.iceCandidate) else signal.iceCandidates.candidatesList
                        remoteCandidates(call, device, list)
                    }
                    else -> Unit
                }
            }
            // The server's "someone calls you" for an idle phone with a stream open. Ours is open
            // only during a call; the offer, which carries the SDP, is how a call arrives here.
            SignalResponse.ResponseCase.INCOMING_CALL -> Log.i(TAG, "incoming-call notice on the stream — the E2EE offer rings, not this")
            else -> Unit
        }
    }

    private fun onStreamClosed(call: ActiveCall, outbound: Channel<SignalRequest>) {
        if (active !== call || call.outbound !== outbound) return
        val canReconnect = CallRules.canReconnectStream(call.mediaConnected, call.streamRetries, call.postMediaReconnects)
        when (CallRules.streamClosedDisposition(call.mediaConnected, awaitingOfferAfterAnswer = false, canReconnect = canReconnect)) {
            CallRules.StreamClosedDisposition.RECONNECT -> {
                call.outbound = null
                if (call.mediaConnected) call.postMediaReconnects += 1 else call.streamRetries += 1
                Log.i(TAG, "signalling stream closed — reopening (retries=${call.streamRetries}, after media=${call.postMediaReconnects})")
                openStream(call)
            }
            CallRules.StreamClosedDisposition.KEEP_ON_MESSAGE_PATH -> {
                call.outbound = null
                Log.i(TAG, "signalling stream closed — call stays on the message path")
            }
            CallRules.StreamClosedDisposition.END_CALL -> {
                Log.w(TAG, "signalling stream gone before media, nothing else bounds the call — ending")
                endActive(CallEndReason.Local("Signal stream closed"))
            }
        }
    }

    private fun sendStream(call: ActiveCall, signal: WebRTCSignal) {
        call.outbound?.trySend(CallSignalWire.routed(signal))
    }

    // ── Signals out, hangup, end ────────────────────────────────────────────

    private fun signal(call: ActiveCall) = WebRTCSignal.newBuilder()
        .setCallId(call.session.id)
        .setSenderDeviceId(myDevice())
        .setTimestamp(nowMs())

    private fun sendE2ee(call: ActiveCall, signal: WebRTCSignal): CompletableDeferred<Boolean> = enqueue(call) { listOf(signal) }

    /**
     * Into the call's queue. [build] runs in the queue — candidates are sealed there, in order with
     * everything else that moves the ratchet. Completes true when every signal it made was taken.
     */
    private fun enqueue(call: ActiveCall, build: suspend () -> List<WebRTCSignal>): CompletableDeferred<Boolean> {
        val done = CompletableDeferred<Boolean>()
        if (call.outbox.trySend(Outgoing(build, done)).isFailure) done.complete(false)
        return done
    }

    private fun sendHangup(call: ActiveCall, reason: HangupReason, origin: CallRules.HangupOrigin) {
        val channels = CallRules.hangupChannels(origin)
        val hangup = CallSignalWire.hangup(call.session.id, myDevice(), reason, nowMs())
        if (CallRules.HangupChannel.E2EE in channels) sendE2ee(call, hangup)
        val onStream = CallRules.HangupChannel.SIGNALING_STREAM in channels && call.outbound != null
        if (onStream) sendStream(call, hangup)
        Log.i(TAG, "hangup reason=$reason origin=$origin e2ee=${CallRules.HangupChannel.E2EE in channels} stream=$onStream ${describe(call)}")
    }

    /** Our side ends it: open the stream so the server hears too, tell the peer, end. */
    private fun endLocally(call: ActiveCall, reason: HangupReason) {
        openStream(call)
        sendHangup(call, reason, CallRules.HangupOrigin.LOCAL)
        endActive(CallEndReason.Hangup(reason))
    }

    private fun begin(session: CallSession, initial: CallState): ActiveCall {
        active?.let {
            ended.remember(it.session.id)
            it.close()
        }
        val call = ActiveCall(session)
        active = call
        _state.value = initial
        _quality.value = CallQuality.GOOD
        // The call's own send queue: one at a time, in order, and nothing from an earlier call ahead of it.
        scope.launch {
            for (o in call.outbox) {
                val ok = try {
                    o.build().map { signals.send(session.peerUserId, it) }.all { it }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "call signal not sent: ${e.message}")
                    false
                }
                o.done.complete(ok)
            }
        }
        return call
    }

    private fun endActive(reason: CallEndReason) {
        val call = active ?: return
        Log.i(TAG, "call end reason=$reason ${describe(call)}")
        call.close()
        active = null
        ended.remember(call.session.id)
        _quality.value = CallQuality.GOOD
        val endedState = CallState.Ended(call.session, reason)
        _state.value = endedState
        scope.launch {
            delay(CallTiming.ENDED_AUTO_CLEAR_MS)
            if (_state.value == endedState) _state.value = CallState.Idle
        }
    }

    private fun myDevice(): String = peers.myDeviceId().orEmpty()

    private fun describe(call: ActiveCall) =
        "call=${call.session.id.take(8)}… ${call.session.direction} state=${_state.value::class.simpleName} " +
            "answered=${call.answeredAtMs != null} media=${call.mediaConnected}"

    private class Outgoing(val build: suspend () -> List<WebRTCSignal>, val done: CompletableDeferred<Boolean>)

    private inner class ActiveCall(val session: CallSession) {
        val outbox = Channel<Outgoing>(Channel.UNLIMITED)
        var peerDevice: String? = null
        var turn: TurnCredentials? = null
        var media: CallMedia? = null
        var outbound: Channel<SignalRequest>? = null
        var stream: Job? = null
        var answeredAtMs: Long? = null
        /** Plaintext SDP of an offer not yet applied; read through [CallRules.offerSdpIsUsable]. */
        var pendingOfferSdp: String? = null
        var ringTimeout: Job? = null
        val pendingRemoteCandidates = mutableListOf<CallIce>()
        val pendingLocalCandidates = mutableListOf<CallIce>()
        var iceFlush: Job? = null
        var streamRetries = 0
        var postMediaReconnects = 0
        var mediaConnected = false
        var iceRestart: Job? = null
        var iceRestartInFlight = false
        var iceRestartAttempts = 0

        /**
         * Timers stop and media goes; the send queue is closed, not cancelled, so a hangup already
         * in it still leaves. The stream lives [CallTiming.STREAM_LINGER_MS] longer for the same reason.
         */
        fun close() {
            listOfNotNull(ringTimeout, iceFlush, iceRestart).forEach { it.cancel() }
            media?.close()
            media = null
            outbox.close()
            outbound?.close()
            stream?.let { job -> scope.launch { delay(CallTiming.STREAM_LINGER_MS); job.cancel() } }
        }
    }

    private companion object {
        const val TAG = "Calls"
    }
}
