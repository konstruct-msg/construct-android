package com.construct.messenger.stealth

import com.construct.messenger.diagnostics.Log
import com.construct.messenger.data.api.MessagingService
import io.grpc.Status
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Build a sealed envelope, send it, and survive one Privacy Pass refusal. **Canon:** iOS
 * `StealthSendRecovery`.
 *
 * Under `MSG_STEALTH_TOKEN_POLICY=enforce` the server refuses an envelope whose token (or intake
 * credential) did not redeem with FAILED_PRECONDITION `privacy_pass:<label>`. The answer is to top
 * up the wallet, rebuild — fresh token, fresh delivery tag, same ratchet payload — and send once
 * more, paying: an envelope that presented a credential carried no token by choice, and presenting
 * the same credential again buys the same refusal (iOS measured that loop on 2026-09-14).
 *
 * On an established session a ratchet message goes as a session envelope; before the peer has
 * answered, a first flight goes sealed whole with our certificate. Either is sealed by the core
 * once, here, and reused in a rebuild — the rebuild changes the payment, not the message. A first
 * flight the core cannot seal throws out of [send]: it is not sent any other way.
 *
 * Never an identified send on this refusal. A server that could force identified sends by refusing
 * tokens could name any sender on demand; a refusal may cost budget or fail the send, not anonymity.
 */
@Singleton
class SealedSend @Inject constructor(
    private val stealthSender: StealthSenderService,
    private val messagingService: MessagingService,
    private val blindTokens: BlindTokenService,
) {
    suspend fun send(
        recipientUserId: String,
        recipientIdentityKey: ByteArray,
        encryptedPayload: ByteArray,
        contentType: SealedEnvelopeType,
        /** A session envelope the core already sealed: a DECRYPTION_ERROR answered along a pair. */
        envelope: ByteArray? = null,
    ): MessagingService.SendResult {
        // Only a wire payload is offered to the core: a DECRYPTION_ERROR it sealed arrives as
        // [envelope], and one it boxed to the identity key goes with a certificate.
        val sessionEnvelope = envelope
            ?: if (contentType == SealedEnvelopeType.GENERIC) stealthSender.sessionEnvelope(recipientIdentityKey, encryptedPayload) else null
        // Anything else may be a first flight — the core says which (any content type: it reads
        // the payload); `null` is the certificate path.
        val firstFlight = if (sessionEnvelope == null) stealthSender.firstFlight(recipientIdentityKey, encryptedPayload) else null
        suspend fun build(afterRejection: Boolean) = stealthSender.buildSealedInner(
            recipientUserId = recipientUserId,
            recipientIdentityKey = recipientIdentityKey,
            encryptedPayload = encryptedPayload,
            contentType = contentType,
            afterCredentialRejection = afterRejection,
            sessionEnvelope = sessionEnvelope,
            firstFlight = firstFlight,
        )
        return try {
            messagingService.sendSealedMessage(build(afterRejection = false))
        } catch (e: Exception) {
            val label = rejectionLabel(e) ?: throw e
            Log.i(TAG, "sealed send refused by enforce ($label) — paying and retrying once")
            blindTokens.replenish(respectCooldown = false)
            messagingService.sendSealedMessage(build(afterRejection = true))
        }
    }

    companion object {
        private const val TAG = "SealedSend"
        private const val PREFIX = "privacy_pass:"

        /** The server's refusal reason ("missing_token", "double_spent", …), or null for any other error. */
        internal fun rejectionLabel(error: Throwable): String? {
            val status = Status.fromThrowable(error)
            val description = status.description ?: return null
            if (status.code != Status.Code.FAILED_PRECONDITION || !description.startsWith(PREFIX)) return null
            return description.removePrefix(PREFIX)
        }
    }
}
