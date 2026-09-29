package com.construct.messenger.domain.usecase

import android.util.Log
import com.construct.messenger.data.api.MessagingService
import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.service.SessionManager
import com.construct.messenger.stealth.StealthPolicy
import com.construct.messenger.stealth.SealedEnvelopeType
import com.construct.messenger.stealth.SealedSend
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import shared.proto.core.v1.EnvelopeOuterClass.ContentType

/**
 * Session control on the wire: since 2026-09-27 that is one message, the DECRYPTION_ERROR
 * (content type 28) — "I could not read your message" — to the device that wrote it.
 *
 * The payload is the core's (`CfeAction.SendDecryptionError`): the unread message's ratchet key and
 * id, sealed to the writer's identity key, fixed length. Nothing here adds to it or decides whether
 * it goes; the core sent the action once per unread message and recorded the message.
 *
 * It replaced END_SESSION (21), which named no state and was rationed by cooldowns, and which this
 * class used to put on the wire with a random pad, on logout to every contact, and on its own
 * cooldown (`decisions/sessions-renew-by-sending.md`, variant B). **Canon:** iOS
 * `MessagingServiceClient.sendDecryptionError`.
 *
 * Stealth: sealed with `SealedInner.content_type = DECRYPTION_ERROR`, fail-closed — no identity key
 * means no send, never an identified control envelope.
 */
@Singleton
class SessionControlUseCase @Inject constructor(
    private val keystoreManager: KeystoreManager,
    private val sessionManager: SessionManager,
    private val messagingService: MessagingService,
    private val stealthPolicy: StealthPolicy,
    private val sealedSend: SealedSend,
) {
    /** Send the core's sealed [payload] to [deviceId] as a DECRYPTION_ERROR. */
    suspend fun sendDecryptionError(deviceId: String, payload: ByteArray): Boolean {
        val target = sessionManager.resolveTarget(deviceId) ?: run {
            Log.w(TAG, "DECRYPTION_ERROR skipped — no account for device ${deviceId.take(8)}…")
            return false
        }
        val accountId = target.accountId
        val myId = keystoreManager.getUserId() ?: return false
        val messageId = UUID.randomUUID().toString().lowercase()
        val timestampMs = System.currentTimeMillis()
        val identity = target.identityPublic

        val result = try {
            if (stealthPolicy.shouldUseSealedSender()) {
                if (identity.isEmpty()) {
                    Log.w(TAG, "DECRYPTION_ERROR stealth-on, no identity key for ${deviceId.take(8)}… — not sent identified")
                    return false
                }
                sealedSend.send(
                    recipientUserId = accountId,
                    recipientIdentityKey = identity,
                    encryptedPayload = payload,
                    contentType = SealedEnvelopeType.DECRYPTION_ERROR,
                )
            } else {
                messagingService.sendMessage(
                    messageId = messageId,
                    senderId = myId,
                    recipientId = accountId,
                    conversationId = "",
                    encryptedPayload = payload,
                    timestampMs = timestampMs,
                    contentType = ContentType.CONTENT_TYPE_DECRYPTION_ERROR,
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "DECRYPTION_ERROR RPC failed ${deviceId.take(8)}…", e)
            return false
        }
        if (!result.success) Log.w(TAG, "DECRYPTION_ERROR rejected ${result.errorCode}")
        else Log.i(TAG, "DECRYPTION_ERROR sent to ${deviceId.take(8)}…")
        return result.success
    }

    private companion object {
        const val TAG = "SessionControl"
    }
}
