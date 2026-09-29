package com.construct.messenger.data.api

import android.os.SystemClock
import com.construct.messenger.diagnostics.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import shared.proto.core.v1.EnvelopeOuterClass.Envelope
import shared.proto.services.v1.MessagingServiceOuterClass.Heartbeat
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
                runStreamOnce(attempt)
                attempt = 0 // clean close → reset backoff
            } catch (e: Exception) {
                _isConnected.value = false
                _events.tryEmit(StreamEvent.Disconnected(e))
                Log.w(TAG, "stream error (attempt ${attempt + 1})", e)
            }
            attempt += 1
            val backoffMs = min(INITIAL_BACKOFF_MS shl min(attempt, 6), MAX_BACKOFF_MS)
            delay(backoffMs)
        }
    }

    private suspend fun runStreamOnce(attempt: Int) {
        val outbound = MutableSharedFlow<MessageStreamRequest>(
            replay = 1,
            extraBufferCapacity = 64,
        )

        // Subscribe frame goes first on every connection (replay = 1 above
        // guarantees the server sees it even if collection starts late).
        outbound.tryEmit(subscribeFrame())

        val scope = CoroutineScope(SupervisorJob())
        val heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                outbound.tryEmit(
                    MessageStreamRequest.newBuilder()
                        .setHeartbeat(
                            Heartbeat.newBuilder().setTimestamp(System.currentTimeMillis()),
                        )
                        .build(),
                )
            }
        }

        // Every heartbeat is answered, so a stream with no inbound frame for STALE_AFTER_MS is
        // dead even though TCP still calls it established — a censoring middlebox or a dropped
        // NAT mapping swallows the traffic and no RST ever arrives. Without this the stream sat
        // on such a socket indefinitely (seen on a RU network, 2026-09-24). Canon: iOS
        // heartbeat watchdog, NetworkTiming.swift ("heartbeatInterval × multiplier = ~60s").
        // A new connection replays from the committed cursor; whatever was tracked on the last one
        // is re-tracked from that replay.
        cursorTracker.reset()
        var lastInboundAt = SystemClock.elapsedRealtime()
        try {
            coroutineScope {
                launch {
                    while (isActive) {
                        delay(WATCHDOG_CHECK_MS)
                        val silentMs = SystemClock.elapsedRealtime() - lastInboundAt
                        if (silentMs > STALE_AFTER_MS) throw StaleStreamException(silentMs)
                    }
                }
                collectStream(outbound, attempt) { lastInboundAt = SystemClock.elapsedRealtime() }
                // The server ended the call cleanly; stop the watchdog so this scope can return.
                coroutineContext.cancelChildren()
            }
        } finally {
            heartbeatJob.cancel()
            _isConnected.value = false
        }
    }

    private suspend fun collectStream(
        outbound: MutableSharedFlow<MessageStreamRequest>,
        attempt: Int,
        onInbound: () -> Unit,
    ) {
        grpcClient.messaging
            .messageStream(outbound.onSubscription {
                _isConnected.value = true
                _events.tryEmit(StreamEvent.Connected(attempt))
                Log.i(TAG, "stream connected (attempt $attempt)")
            })
            .collect { response ->
                onInbound()
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
        const val HEARTBEAT_INTERVAL_MS = 25_000L
        const val STALE_AFTER_MS = 60_000L
        const val WATCHDOG_CHECK_MS = 5_000L
        const val INITIAL_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 30_000L
    }
}
