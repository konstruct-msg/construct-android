package com.construct.messenger.stealth

import android.util.Log
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
    ): MessagingService.SendResult {
        suspend fun build(afterRejection: Boolean) = stealthSender.buildSealedInner(
            recipientUserId = recipientUserId,
            recipientIdentityKey = recipientIdentityKey,
            encryptedPayload = encryptedPayload,
            contentType = contentType,
            afterCredentialRejection = afterRejection,
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
