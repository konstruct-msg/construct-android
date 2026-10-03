package com.construct.messenger.data.api

import io.grpc.Status
import com.construct.messenger.transport.TransportRoute
import com.construct.messenger.transport.TransportEvents
import com.construct.messenger.transport.RouteObservingInterceptor
import com.construct.messenger.veil.VeilProxy
import com.construct.messenger.data.auth.AuthSessionManager
import android.os.SystemClock
import com.construct.messenger.diagnostics.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import shared.proto.core.v1.EnvelopeOuterClass.Envelope
import shared.proto.services.v1.MessagingServiceOuterClass.MessageStreamRequest
import shared.proto.services.v1.MessagingServiceOuterClass.SubscribeRequest
import shared.proto.signaling.v1.Presence.DeliveryReceipt
import shared.proto.signaling.v1.Presence.TypingIndicator

/**
 * Bidi `MessageStream` — the live receive path. Mirrors iOS
 * `MessageStreamManager` in miniature: one persistent stream, subscribe on
 * open, heartbeats, cursor-based resume, reconnect with capped backoff.
 *
 * ## Lifecycle
 *
 * [start] opens the stream on the injected [scope]; [stop] cancels it.
 * On every (re)connect the service sends a [SubscribeRequest] with the current
 * conversation ids ([updateSubscriptions]) and the persisted `since_cursor`,
 * so the server replays only unseen messages.
 *
 * ## Consuming
 *
 * Collect [events] — a hot [SharedFlow] of [StreamEvent]. Decryption/routing
 * belongs to the layer above (MessageRouter-equivalent), NOT here: this class
 * is transport only. Sealed-sender envelopes arrive with an empty `sender` and
 * `sealed_sender.sealed_inner` set — resolve identity via
 * [com.construct.messenger.stealth.StealthSenderService.resolveSender].
 *
 * Sends go through [MessagingService] (unary) — same split as iOS, where the
 * stream's send branch is unused.
 */
@Singleton
class MessageStreamService @Inject constructor(
    private val grpcClient: GrpcClient,
    private val cursorTracker: StreamCursorTracker,
    private val authSession: AuthSessionManager,
    private val veil: VeilProxy,
    private val transportEvents: TransportEvents,
) {
    sealed interface StreamEvent {
        data class Message(val envelope: Envelope) : StreamEvent
        data class Receipt(val receipt: DeliveryReceipt) : StreamEvent
        data class Typing(val typing: TypingIndicator) : StreamEvent
        data class Connected(val attempt: Int) : StreamEvent
        data class Disconnected(val cause: Throwable?) : StreamEvent
    }

    private val _events = MutableSharedFlow<StreamEvent>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Hot stream of transport events. Late subscribers see only new events. */
    val events: SharedFlow<StreamEvent> = _events.asSharedFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    /** Wall-clock time the server last answered a heartbeat — the Network screen's "last heartbeat". */
    private val _lastHeartbeatAt = MutableStateFlow<Long?>(null)
    val lastHeartbeatAt: StateFlow<Long?> = _lastHeartbeatAt.asStateFlow()

    private val subscriptions = MutableStateFlow<List<String>>(emptyList())
    private var connectJob: Job? = null

    /** Replace the subscribed conversation set (applied on next (re)connect;
     * live re-subscribe is a follow-up — reconnect is cheap for now). */
    fun updateSubscriptions(conversationIds: List<String>) {
        subscriptions.value = conversationIds
    }

    /** Opens the stream and keeps it alive until [stop]. Idempotent. */
    fun start(scope: CoroutineScope) {
        if (connectJob?.isActive == true) return
        connectJob = scope.launch { connectLoop() }
    }

    fun stop() {
        connectJob?.cancel()
        connectJob = null
        _isConnected.value = false
    }

    private suspend fun connectLoop() {
        var attempt = 0
        // The loop's own coroutine, not `connectJob`: on `Dispatchers.IO` the launched coroutine can
        // start before `start` has assigned the field, read null, and leave the loop before its
        // first attempt — the stream then never opens and nothing logs why. Seen on the emulator
        // 2026-09-27, every launch, once the pool had free threads at start-up.
        while (currentCoroutineContext().isActive) {
            try {
                // A stream opened on an expired token is accepted and then hears nothing; the
                // stale check only notices after a minute of silence.
                authSession.ensureFresh()
            } catch (e: Exception) {
                Log.w(TAG, "token refresh before the stream failed", e)
            }
            val generation = grpcClient.generation
            val via = grpcClient.target
            framesThisConnection = 0
            var failure: Throwable? = null
            try {
                runStreamOnce(attempt)
            } catch (e: Exception) {
                failure = e
                _isConnected.value = false
                _events.tryEmit(StreamEvent.Disconnected(e))
                Log.w(TAG, "stream error (attempt ${attempt + 1})", e)
            }
            reportToRouter(failure, generation, via)
            // A connection that carried frames was a working one: the next retry is not the
            // (attempt + 1)-th in a row.
            if (framesThisConnection > 0) attempt = 0
            attempt += 1
            val backoffMs = min(INITIAL_BACKOFF_MS shl min(attempt, 6), MAX_BACKOFF_MS)
            delay(backoffMs)
        }
    }

    /** Frames received on the current connection; 0 means it never reached the data plane. */
    @Volatile
    private var framesThisConnection = 0

    /**
     * Tell the route machine how this connection ended. Canon: iOS `MessageStreamManager` posting
     * `streamFailed` — a stream that reached the data plane and then died is a different claim
     * from one that never opened, and on a censored network it is the one that happens.
     */
    private fun reportToRouter(failure: Throwable?, generation: Long, via: TransportRoute.Target) {
        // Cut by our own route switch or a fresh client — not about the network.
        if (grpcClient.generation != generation) return
        val midSession = framesThisConnection > 0
        val kind = when {
            failure == null -> if (midSession) TransportRoute.StreamFailure.MID_SESSION_CLOSED else TransportRoute.StreamFailure.CLOSED
            failure is StaleStreamException ->
                if (midSession) TransportRoute.StreamFailure.MID_SESSION_TIMEOUT else TransportRoute.StreamFailure.OPEN_TIMEOUT
            else -> {
                // An answer from the server (auth, application) says the path works.
                val status = Status.fromThrowable(failure)
                if (!RouteObservingInterceptor.classify(status, via).isTransport) return
                if (midSession) TransportRoute.StreamFailure.MID_SESSION_UNKNOWN else TransportRoute.StreamFailure.TRANSPORT_UNKNOWN
            }
        }
        if (localProxyGone(failure, via, veil::isAlive)) {
            // The local proxy is gone: that is a hard relay failure, and it rotates.
            transportEvents.post(TransportRoute.Event.RpcFailed(TransportRoute.RpcFailure.STALE_LOCAL_PROXY, via, foreground = true))
            return
        }
        val method = if (via is TransportRoute.Target.Veil) TransportRoute.StreamMethod.VEIL else TransportRoute.StreamMethod.H2
        transportEvents.post(TransportRoute.Event.StreamFailed(method, kind, via))
    }

    private suspend fun runStreamOnce(attempt: Int) {
        val outbound = MutableSharedFlow<MessageStreamRequest>(
            replay = 1,
            extraBufferCapacity = 64,
        )

        // Subscribe frame goes first on every connection (replay = 1 above
        // guarantees the server sees it even if collection starts late).
        outbound.tryEmit(subscribeFrame())

        // No heartbeat from this side: the server sends one every 30 s on every stream
        // (messaging-service `stream_heartbeat_interval_secs`) and answers ours with nothing more
        // than an ack, so ours only doubled the radio's wake-ups — on a mobile network each keeps
        // the radio up for seconds after it (testers, battery, 2026-10-03). Its arrival is what the
        // watchdog below waits for. iOS still sends its own; it holds no stream in the background.
        //
        // A stream with no inbound frame for STALE_AFTER_MS is dead even though TCP still calls it
        // established — a censoring middlebox or a dropped NAT mapping swallows the traffic and no
        // RST ever arrives. Without this the stream sat on such a socket indefinitely (seen on a RU
        // network, 2026-09-24). Canon: iOS heartbeat watchdog, NetworkTiming.swift
        // ("heartbeatInterval × multiplier = ~60s").
        // A new connection replays from the committed cursor; whatever was tracked on the last one
        // is re-tracked from that replay.
        cursorTracker.reset()
        var lastInboundAt = SystemClock.elapsedRealtime()
        try {
            coroutineScope {
                launch {
                    // Sleeps until the stream would turn stale, not on a short tick: a wake-up
                    // every few seconds for nothing is what the battery pays for.
                    while (isActive) {
                        val silentMs = SystemClock.elapsedRealtime() - lastInboundAt
                        if (silentMs > STALE_AFTER_MS) throw StaleStreamException(silentMs)
                        delay(STALE_AFTER_MS - silentMs + 1)
                    }
                }
                collectStream(outbound, attempt) { lastInboundAt = SystemClock.elapsedRealtime() }
                // The server ended the call cleanly; stop the watchdog so this scope can return.
                coroutineContext.cancelChildren()
            }
        } finally {
            _isConnected.value = false
        }
    }

    private suspend fun collectStream(
        outbound: MutableSharedFlow<MessageStreamRequest>,
        attempt: Int,
        onInbound: () -> Unit,
    ) {
        val via = grpcClient.target
        grpcClient.messaging
            .messageStream(outbound)
            .collect { response ->
                onInbound()
                // Connected means the server answered, not that the call was placed: the stream
                // that opened and heard nothing on 2026-09-29 was logged "connected" for a minute.
                if (framesThisConnection++ == 0) {
                    _isConnected.value = true
                    _events.tryEmit(StreamEvent.Connected(attempt))
                    Log.i(TAG, "stream connected (attempt $attempt)")
                    val method = if (via is TransportRoute.Target.Veil) TransportRoute.StreamMethod.VEIL else TransportRoute.StreamMethod.H2
                    transportEvents.post(TransportRoute.Event.StreamOpened(method, via))
                }
                // Never committed here: the cursor tells the server what it may delete, so it
                // moves only when the message reaches a durable end (StreamCursorTracker).
                val cursor = response.streamCursor.takeIf { response.hasStreamCursor() && it.isNotEmpty() }
                if (cursor != null) {
                    if (response.hasMessage()) {
                        cursorTracker.track(response.message.messageId, cursor)
                    } else {
                        cursorTracker.trackResolved(cursor)
                    }
                }
                when {
                    response.hasMessage() -> _events.tryEmit(StreamEvent.Message(response.message))
                    response.hasReceipt() -> _events.tryEmit(StreamEvent.Receipt(response.receipt))
                    response.hasTyping() -> _events.tryEmit(StreamEvent.Typing(response.typing))
                    response.hasHeartbeatAck() -> _lastHeartbeatAt.value = System.currentTimeMillis()
                    // acks/errors/presence: no consumer yet — extend StreamEvent when needed.
                    else -> Log.d(TAG, "unhandled stream frame: ${response.responseCase}")
                }
            }
    }

    private class StaleStreamException(silentMs: Long) :
        java.io.IOException("no inbound frame for ${silentMs / 1000}s — treating stream as dead")

    private fun subscribeFrame(): MessageStreamRequest {
        val builder = SubscribeRequest.newBuilder()
            .addAllConversationIds(subscriptions.value)
            .setIncludePresence(true)
        cursorTracker.committedCursor()?.let(builder::setSinceCursor)
        return MessageStreamRequest.newBuilder().setSubscribe(builder).build()
    }

    private companion object {
        const val TAG = "MessageStream"
        const val STALE_AFTER_MS = 60_000L
        const val INITIAL_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 30_000L
    }
}

/**
 * Whether a stream that ended over VEIL ended because the local proxy is gone. A refused loopback
 * connection says so by itself; [proxyAlive] (`veil_is_alive`) is asked only otherwise — it answers
 * from the coordinator's session, not the listener, and on iOS said "alive" with the socket closed
 * after a suspension (TODO 102). Trusted alone, a dead proxy would be reconnected to forever rather
 * than replaced. Unary RPCs already rotate on a refused connection (`RouteObservingInterceptor`).
 */
internal fun localProxyGone(failure: Throwable?, via: TransportRoute.Target, proxyAlive: () -> Boolean): Boolean {
    if (via !is TransportRoute.Target.Veil) return false
    if (failure != null &&
        RouteObservingInterceptor.classify(Status.fromThrowable(failure), via) == TransportRoute.RpcFailure.STALE_LOCAL_PROXY
    ) return true
    return !proxyAlive()
}
