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
 * 2. Stealth on → build SealedInner via [StealthSenderService.buildSealedInner], then
 *    [sendSealedMessage]: `SendSealedMessage` over [GrpcClient.sealedMessaging], the channel
 *    with no token on it. There is no sealed branch on [sendMessage]: a sealed envelope next to
 *    a Bearer told the operator the sender of every sealed message, live (TODO 55.1). The
 *    server refuses that path once `MSG_REJECT_LEGACY_SEALED_SENDER` is on.
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

    /** Identified send: sender and content type on the outer envelope, authenticated channel. */
    suspend fun sendMessage(
        messageId: String,
        senderId: String,
        recipientId: String,
        conversationId: String,
        encryptedPayload: ByteArray,
        timestampMs: Long,
        contentType: ContentType = ContentType.CONTENT_TYPE_E2EE_SIGNAL,
    ): SendResult {
        val envelope = Envelope.newBuilder().apply {
            setMessageId(messageId)
            recipient = UserId.newBuilder().setUserId(recipientId).build()
            setTimestamp(timestampMs)
            sender = UserId.newBuilder().setUserId(senderId).build()
            // conversation_id is never written — names the pair in the clear
            // (WIRE_FORMAT_RULES). Parameter kept so call sites compile.
            setContentType(contentType)
            setEncryptedPayload(ByteString.copyFrom(encryptedPayload))
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
     * Sealed send: `SendSealedMessage` over the unauthenticated channel. Carries ONLY the sealed
     * envelope — no outer Envelope at all. Idempotency = SealedInner.delivery_tag (server-side
     * replay check).
     */
    suspend fun sendSealedMessage(sealedInner: ByteArray): SendResult {
        val attemptId = UUID.randomUUID().toString().lowercase()
        val request = buildSealedRequest(sealedInner, System.currentTimeMillis() / 1000, attemptId)

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
         * The only place a [SealedSenderEnvelope] is built. `timestamp` is the send time in
         * **seconds**: federation forwarding hands it to the destination, which refuses one more
         * than five minutes off its clock (`validate_federation_timestamp`). Stamped here rather
         * than taken from the caller, whose timestamp is the message's and is in milliseconds.
         * **Canon:** iOS `MessagingServiceClient.buildSealedRequest`.
         */
        internal fun buildSealedRequest(
            sealedInner: ByteArray,
            nowSeconds: Long,
            attemptId: String,
        ): SendSealedMessageRequest = SendSealedMessageRequest.newBuilder()
            .setSealedSender(
                SealedSenderEnvelope.newBuilder()
                    .setSealedInner(ByteString.copyFrom(sealedInner))
                    .setTimestamp(nowSeconds)
                    .build(),
            )
            .setAttemptId(attemptId)
            .build()
    }
}
