package com.construct.messenger.service

import android.util.Log
import com.construct.messenger.data.api.MessageStreamService
import com.construct.messenger.data.api.MessageStreamService.StreamEvent
import com.construct.messenger.stealth.StealthSenderService
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import shared.proto.core.v1.EnvelopeOuterClass.ContentType
import shared.proto.core.v1.EnvelopeOuterClass.Envelope
import shared.proto.signaling.v1.Presence.DeliveryReceipt
import shared.proto.signaling.v1.Presence.TypingIndicator

/**
 * Normalizes raw stream frames into domain events — the Android mirror of iOS
 * `MessageRouter` (transport → routing → consumers).
 *
 * Responsibilities (in order, per incoming envelope):
 *  1. **Dedup** by message id (LRU window — the server re-delivers on
 *     reconnect/cursor overlap; at-least-once + dedup is the contract).
 *  2. **Sealed-sender resolution**: envelopes with `sealed_sender` arrive with
 *     no sender and an empty outer payload; identity + real content type +
 *     E2EE payload come from [StealthSenderService.resolveSender]. Unresolvable
 *     sealed messages are dropped with a log — non-fatal by design (same as iOS).
 *  3. **Classification** by content type: control frames (session lifecycle)
 *     are separated from user-visible messages so consumers never try to
 *     decrypt control payloads.
 *
 * The router does NOT decrypt: decryption + session healing belong to the
 * processor layer (next step — see `docs/GRPC_LAYER.md` §3.1; long-term that
 * layer should drive `OrchestratorCore.handleEvent`, which parses the wire
 * payload inside Rust, rather than the component-based `decryptMessage`).
 *
 * Consume via [routed] — a hot flow; repositories collect and own persistence.
 */
@Singleton
class MessageRouter @Inject constructor(
    private val stream: MessageStreamService,
    private val stealthSender: StealthSenderService,
) {

    sealed interface RoutedEvent {
        /** User-visible E2EE message, ready for the decrypt/persist pipeline. */
        data class Incoming(val message: IncomingMessage) : RoutedEvent

        /** Session-control frame (reset/sync/ping/… — never shown to the user). */
        data class Control(val message: IncomingMessage) : RoutedEvent

        data class Receipt(val receipt: DeliveryReceipt) : RoutedEvent
        data class Typing(val typing: TypingIndicator) : RoutedEvent
        data class ConnectionChanged(val connected: Boolean) : RoutedEvent
    }

    /** Envelope normalized to one shape regardless of sealed/identified arrival. */
    data class IncomingMessage(
        val messageId: String,
        val senderId: String,
        val contentType: ContentType,
        val encryptedPayload: ByteArray,
        val timestampMs: Long,
        val viaSealedSender: Boolean,
    )

    private val _routed = MutableSharedFlow<RoutedEvent>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val routed: SharedFlow<RoutedEvent> = _routed.asSharedFlow()

    /** LRU dedup window. Access is confined to the single collector coroutine. */
    private val seenMessageIds = object : LinkedHashMap<String, Unit>(DEDUP_WINDOW, 0.75f, false) {
        override fun removeEldestEntry(eldest: Map.Entry<String, Unit>) = size > DEDUP_WINDOW
    }

    private var routeJob: Job? = null

    /** Starts routing (idempotent). Call alongside [MessageStreamService.start]. */
    fun start(scope: CoroutineScope) {
        if (routeJob?.isActive == true) return
        routeJob = scope.launch {
            stream.events.collect { event ->
                when (event) {
                    is StreamEvent.Message -> route(event.envelope)
                    is StreamEvent.Receipt -> _routed.tryEmit(RoutedEvent.Receipt(event.receipt))
                    is StreamEvent.Typing -> _routed.tryEmit(RoutedEvent.Typing(event.typing))
                    is StreamEvent.Connected -> _routed.tryEmit(RoutedEvent.ConnectionChanged(true))
                    is StreamEvent.Disconnected -> _routed.tryEmit(RoutedEvent.ConnectionChanged(false))
                }
            }
        }
    }

    fun stop() {
        routeJob?.cancel()
        routeJob = null
    }

    /** Feed a catch-up envelope (pending-messages unary) through the same path as the stream. */
    fun ingest(envelope: Envelope) = route(envelope)

    private fun route(envelope: Envelope) {
        val messageId = envelope.messageId
        if (messageId.isNotEmpty() && seenMessageIds.put(messageId, Unit) != null) {
            Log.d(TAG, "duplicate message ${messageId.take(8)}… — dropped")
            return
        }

        val incoming = normalize(envelope) ?: return
        val event = if (incoming.contentType.isControl()) {
            RoutedEvent.Control(incoming)
        } else {
            RoutedEvent.Incoming(incoming)
        }
        _routed.tryEmit(event)
    }

    private fun normalize(envelope: Envelope): IncomingMessage? =
        normalizeEnvelope(envelope) { stealthSender.resolveSender(it) }

    private companion object {
        const val TAG = "MessageRouter"
        const val DEDUP_WINDOW = 512
    }
}

/**
 * Collapses sealed and identified envelopes into one [MessageRouter.IncomingMessage]
 * shape. Pure function (resolution injected) — unit-tested without Hilt/transport.
 */
internal fun normalizeEnvelope(
    envelope: Envelope,
    resolveSealed: (ByteArray) -> StealthSenderService.ResolvedSender?,
): MessageRouter.IncomingMessage? {
    if (envelope.hasSealedSender()) {
        val inner = envelope.sealedSender.sealedInner.toByteArray()
        val resolved = resolveSealed(inner) ?: run {
            Log.w("MessageRouter", "sealed message ${envelope.messageId.take(8)}… unresolvable — dropped")
            return null
        }
        return MessageRouter.IncomingMessage(
            messageId = envelope.messageId,
            senderId = resolved.senderId,
            contentType = resolved.contentType,
            encryptedPayload = resolved.encryptedPayload,
            timestampMs = envelope.timestamp,
            viaSealedSender = true,
        )
    }

    val senderId = envelope.sender.userId
    if (senderId.isEmpty()) {
        Log.w("MessageRouter", "identified message ${envelope.messageId.take(8)}… without sender — dropped")
        return null
    }
    return MessageRouter.IncomingMessage(
        messageId = envelope.messageId,
        senderId = senderId,
        contentType = envelope.contentType,
        encryptedPayload = envelope.encryptedPayload.toByteArray(),
        timestampMs = envelope.timestamp,
        viaSealedSender = false,
    )
}

/** Session-lifecycle frames the UI must never render or try to decrypt as chat. */
internal fun ContentType.isControl(): Boolean = when (this) {
    ContentType.CONTENT_TYPE_KEY_EXCHANGE,
    ContentType.CONTENT_TYPE_SESSION_RESET,
    ContentType.CONTENT_TYPE_KEY_SYNC,
    ContentType.CONTENT_TYPE_SENDER_SYNC,
    ContentType.CONTENT_TYPE_SESSION_RESET_INIT,
    ContentType.CONTENT_TYPE_SESSION_PING,
    ContentType.CONTENT_TYPE_SESSION_READY,
    ContentType.CONTENT_TYPE_HEARTBEAT,
    -> true
    else -> false
}
