package com.construct.messenger.service

import com.construct.messenger.util.ServerMessageOrder
import com.construct.messenger.diagnostics.Log
import com.construct.messenger.data.api.MessageStreamService
import com.construct.messenger.data.api.MessageStreamService.StreamEvent
import com.construct.messenger.stealth.OwnDeviceCopy
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
import shared.proto.signaling.v1.Presence.TypingIndicator
import uniffi.construct_core.SenderCertificate

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
 * processor layer, which drives `OrchestratorCore.handleEvent`. The core parses
 * the wire payload and returns typed actions; Kotlin does not recreate the
 * component-based routing decision.
 *
 * Consume via [routed] — a hot flow; repositories collect and own persistence.
 */
@Singleton
class MessageRouter @Inject constructor(
    private val stream: MessageStreamService,
    private val stealthSender: StealthSenderService,
    private val orderBook: ServerOrderBook,
) {

    sealed interface RoutedEvent {
        /** User-visible E2EE message, ready for the decrypt/persist pipeline. */
        data class Incoming(val message: IncomingMessage) : RoutedEvent

        /** Session-control frame (reset/sync/ping/… — never shown to the user). */
        data class Control(val message: IncomingMessage) : RoutedEvent

        data class Typing(val typing: TypingIndicator) : RoutedEvent
        data class ConnectionChanged(val connected: Boolean) : RoutedEvent
    }

    /** Envelope normalized to one shape regardless of sealed/identified arrival.
     *
     * [senderCertificate] is the certificate the message came with — unsealed from a sealed
     * envelope, or carried in the clear by a SENDER_SYNC (`OwnDeviceCopy`) — unchecked. It names
     * the device that wrote the message ([senderDeviceId]) and is the only thing a first message
     * opens a session from; the core checks its signature when it does.
     *
     * A session envelope carries no certificate: [envelopeSession] is the session whose tag
     * matched — handed to the core with the message — and [envelopeDevice] the device it is with. */
    data class IncomingMessage(
        val messageId: String,
        val senderId: String,
        val contentType: ContentType,
        val encryptedPayload: ByteArray,
        val timestampMs: Long,
        val viaSealedSender: Boolean,
        val senderCertificate: SenderCertificate? = null,
        val envelopeSession: String? = null,
        val envelopeDevice: String? = null,
    ) {
        val senderDeviceId: String get() = senderCertificate?.deviceId ?: envelopeDevice.orEmpty()
    }

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
                if (event is StreamEvent.Message) route(event.envelope, ServerMessageOrder.key(event.envelope.serverMetadata.serverTimestamp, event.envelope.serverMetadata.messageNumber, event.cursor))
                else passThrough(event)?.let { _routed.tryEmit(it) }
            }
        }
    }

    fun stop() {
        routeJob?.cancel()
        routeJob = null
    }

    /**
     * Feed a catch-up envelope (pending-messages unary) through the same path as the stream;
     * [orderKey] is its place in the server's order, as the page gave it.
     */
    suspend fun ingest(envelope: Envelope, orderKey: String?) = route(envelope, orderKey)

    private suspend fun route(envelope: Envelope, orderKey: String?) {
        val messageId = envelope.messageId
        if (messageId.isNotEmpty() && seenMessageIds.put(messageId, Unit) != null) {
            Log.d(TAG, "duplicate message ${messageId.take(8)}… — dropped")
            return
        }
        orderBook.remember(messageId, orderKey)

        val incoming = normalize(envelope) ?: return
        val event = if (incoming.contentType.isControl()) {
            RoutedEvent.Control(incoming)
        } else {
            RoutedEvent.Incoming(incoming)
        }
        _routed.tryEmit(event)
    }

    private suspend fun normalize(envelope: Envelope): IncomingMessage? =
        normalizeEnvelope(envelope) { stealthSender.resolveSender(it) }

    private companion object {
        const val TAG = "MessageRouter"
        const val DEDUP_WINDOW = 512
    }
}

/**
 * What a stream event other than a message becomes, or null for one that goes no further.
 *
 * A delivery receipt the stream relays in the clear goes no further. TLS ends at the server, so
 * nothing in it is authenticated: its value is whatever the server chose, and the checkmark is the
 * one thing the UI says about the *other person*. The peer's own statement arrives end to end
 * (KNST type 14, `ProcessorEffectsImpl`), and that is the only thing that marks a message
 * delivered. The server stopped relaying these on 2026-10-06, so one arriving is either a stale
 * server or not a peer at all — logged, never counted. The cursor needs nothing from here:
 * `MessageStreamService` resolves a frame without a message as it arrives.
 * **Canon:** iOS `DeliveryStatusTransition.marksDelivered`, `StreamLifecycleCoordinator.handleDeliveryReceipts`.
 */
internal fun passThrough(event: StreamEvent): MessageRouter.RoutedEvent? = when (event) {
    is StreamEvent.Message -> null
    is StreamEvent.Receipt -> {
        val count = if (event.receipt.hasDirect()) event.receipt.direct.messageIdsCount else 0
        Log.e("MessageRouter", "SECURITY[receipt_gate]: refused $count relayed plaintext receipt(s) — the checkmark comes from the peer's E2E receipt only")
        null
    }
    is StreamEvent.Typing -> MessageRouter.RoutedEvent.Typing(event.typing)
    is StreamEvent.Connected -> MessageRouter.RoutedEvent.ConnectionChanged(true)
    is StreamEvent.Disconnected -> MessageRouter.RoutedEvent.ConnectionChanged(false)
}

/**
 * Collapses sealed and identified envelopes into one [MessageRouter.IncomingMessage]
 * shape. Pure function (resolution injected) — unit-tested without Hilt/transport.
 */
internal suspend fun normalizeEnvelope(
    envelope: Envelope,
    resolveSealed: suspend (ByteArray) -> StealthSenderService.ResolvedSender?,
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
            senderCertificate = resolved.senderCertificate,
            envelopeSession = resolved.envelopeSession,
            envelopeDevice = resolved.envelopeDevice,
        )
    }

    val senderId = envelope.sender.userId
    if (senderId.isEmpty()) {
        Log.w("MessageRouter", "identified message ${envelope.messageId.take(8)}… without sender — dropped")
        return null
    }
    if (envelope.contentType == ContentType.CONTENT_TYPE_SENDER_SYNC) {
        // A copy from a sibling, unsealed by design: its certificate rides beside the wire payload.
        val copy = OwnDeviceCopy.unwrap(envelope.encryptedPayload.toByteArray()) ?: run {
            Log.w("MessageRouter", "sender-sync ${envelope.messageId.take(8)}… is not an OwnDeviceCopy — dropped")
            return null
        }
        return MessageRouter.IncomingMessage(
            messageId = envelope.messageId,
            senderId = senderId,
            contentType = envelope.contentType,
            encryptedPayload = copy.wirePayload,
            timestampMs = envelope.timestamp,
            viaSealedSender = false,
            senderCertificate = copy.certificate,
        )
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
    ContentType.CONTENT_TYPE_DECRYPTION_ERROR,
    ContentType.CONTENT_TYPE_KEY_SYNC,
    ContentType.CONTENT_TYPE_SENDER_SYNC,
    ContentType.CONTENT_TYPE_SESSION_RESET_INIT,
    ContentType.CONTENT_TYPE_SESSION_PING,
    ContentType.CONTENT_TYPE_SESSION_READY,
    ContentType.CONTENT_TYPE_HEARTBEAT,
    -> true
    else -> false
}
