package com.construct.messenger.data.api

import android.util.Log
import com.construct.messenger.stealth.StealthPolicy
import com.construct.messenger.stealth.StealthSenderService
import com.google.protobuf.ByteString
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import shared.proto.core.v1.EnvelopeOuterClass.ContentType
import shared.proto.core.v1.EnvelopeOuterClass.Envelope
import shared.proto.core.v1.EnvelopeOuterClass.SealedSenderEnvelope
import shared.proto.core.v1.Identity.UserId
import shared.proto.services.v1.MessagingServiceOuterClass.GetPendingMessagesRequest
import shared.proto.services.v1.MessagingServiceOuterClass.GetPendingMessagesResponse
import shared.proto.services.v1.MessagingServiceOuterClass.SendMessageRequest
import shared.proto.services.v1.MessagingServiceOuterClass.SendSealedMessageRequest

/**
 * Unary MessagingService RPCs: send (identified or sealed) + pending-message fetch.
 * Mirrors iOS `MessagingServiceClient`.
 *
 * ## Send-path decision tree (matches iOS, stealth-sealed-sender-v2)
 *
 * 1. [StealthPolicy.shouldUseSealedSender] false → identified [sendMessage]
 *    (sender + conversation_id + content_type on the outer envelope).
 * 2. Stealth on → build SealedInner via [StealthSenderService.buildSealedInner], then:
 *    - [SEALED_UNAUTHENTICATED_TRANSPORT] on → [sendSealedMessage]: the Phase 2
 *      `SendSealedMessage` RPC over [GrpcClient.sealedMessaging] (separate
 *      unauthenticated channel — real sender anonymity);
 *    - off → legacy sealed-over-`SendMessage`: SealedInner rides the outer
 *      envelope's `sealed_sender` field on the authenticated channel
 *      (functionally correct, but the transport still identifies the sender —
 *      L1 in the decision doc).
 *
 * Callers own retry/backoff; this layer is a thin, stateless RPC adapter.
 */
@Singleton
class MessagingService @Inject constructor(
    private val grpcClient: GrpcClient,
) {

    data class SendResult(
        val messageId: String,
        val success: Boolean,
        val errorCode: String,
        val retryable: Boolean,
        val retryAfterMs: Long,
        val attemptId: String,
    )

    /**
     * Identified send, or legacy sealed-over-SendMessage when [sealedInner] is
     * non-null (Phase 3: the sealed branch must NOT set sender/conversation_id/
     * content_type on the outer envelope — the real content type travels inside
     * SealedInner).
     */
    suspend fun sendMessage(
        messageId: String,
        senderId: String,
        recipientId: String,
        conversationId: String,
        encryptedPayload: ByteArray,
        timestampMs: Long,
        contentType: ContentType = ContentType.CONTENT_TYPE_E2EE_SIGNAL,
        sealedInner: ByteArray? = null,
    ): SendResult {
        val envelope = Envelope.newBuilder().apply {
            setMessageId(messageId)
            recipient = UserId.newBuilder().setUserId(recipientId).build()
            setTimestamp(timestampMs)
            if (sealedInner != null && sealedInner.isNotEmpty()) {
                // STEALTH: no sender, no conversation_id, no real content_type.
                sealedSender = SealedSenderEnvelope.newBuilder()
                    .setSealedInner(ByteString.copyFrom(sealedInner))
                    .build()
            } else {
                sender = UserId.newBuilder().setUserId(senderId).build()
                setConversationId(conversationId)
                setContentType(contentType)
                setEncryptedPayload(ByteString.copyFrom(encryptedPayload))
            }
        }.build()

        val attemptId = UUID.randomUUID().toString().lowercase()
        val request = SendMessageRequest.newBuilder()
            .setMessage(envelope)
            .setIdempotencyKey(messageId)
            .setAttemptId(attemptId)
            .build()

        val response = grpcClient.messaging.sendMessage(request)
        return SendResult(
            messageId = response.messageId,
            success = response.success,
            errorCode = if (response.hasError()) response.error.errorCode.name else "",
            retryable = if (response.hasError()) response.error.retryable else true,
            retryAfterMs = if (response.hasError() && response.error.hasRetryAfterMs()) {
                response.error.retryAfterMs
            } else 0,
            attemptId = if (response.hasAttemptId()) response.attemptId else attemptId,
        ).also {
            Log.i(TAG, "sendMessage ${if (it.success) "sent" else "failed(${it.errorCode})"} attemptId=${it.attemptId}")
        }
    }

    /**
     * Phase 2 sealed send: `SendSealedMessage` over the unauthenticated channel.
     * Carries ONLY the sealed envelope — no outer Envelope at all.
     * Idempotency = SealedInner.delivery_tag (server-side replay check).
     */
    suspend fun sendSealedMessage(sealedInner: ByteArray): SendResult {
        val attemptId = UUID.randomUUID().toString().lowercase()
        val request = SendSealedMessageRequest.newBuilder()
            .setSealedSender(
                SealedSenderEnvelope.newBuilder()
                    .setSealedInner(ByteString.copyFrom(sealedInner))
                    .build(),
            )
            .setAttemptId(attemptId)
            .build()

        val response = grpcClient.sealedMessaging.sendSealedMessage(request)
        return SendResult(
            messageId = response.messageId,
            success = response.success,
            errorCode = if (response.hasError()) response.error.errorCode.name else "",
            retryable = if (response.hasError()) response.error.retryable else true,
            retryAfterMs = if (response.hasError() && response.error.hasRetryAfterMs()) {
                response.error.retryAfterMs
            } else 0,
            attemptId = if (response.hasAttemptId()) response.attemptId else attemptId,
        ).also {
            Log.i(TAG, "sendSealedMessage ${if (it.success) "sent" else "failed(${it.errorCode})"} attemptId=${it.attemptId}")
        }
    }

    /** Unary catch-up fetch (background fetch / cold start before the stream opens). */
    suspend fun getPendingMessages(sinceCursor: String? = null, limit: Int = 100): GetPendingMessagesResponse {
        val request = GetPendingMessagesRequest.newBuilder().apply {
            setLimit(limit)
            sinceCursor?.let { setSinceCursor(it) }
        }.build()
        return grpcClient.messaging.getPendingMessages(request)
    }

    companion object {
        private const val TAG = "MessagingService"

        /**
         * Route sealed sends over the Phase 2 unauthenticated RPC instead of the
         * legacy sealed-over-SendMessage branch. Mirrors iOS
         * `FeatureFlags.sealedSenderUnauthenticatedTransport` — keep the two in
         * sync when flipping (see stealth-sealed-sender-v2 decision doc §4).
         */
        const val SEALED_UNAUTHENTICATED_TRANSPORT = false
    }
}
