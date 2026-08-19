package com.construct.messenger.domain.usecase

import android.util.Log
import com.construct.messenger.data.api.MessagingService
import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.data.local.SessionStateStore
import com.construct.messenger.data.local.db.UserDao
import com.construct.messenger.service.SessionManager
import com.construct.messenger.stealth.StealthPolicy
import com.construct.messenger.stealth.StealthSenderService
import java.security.SecureRandom
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import shared.proto.core.v1.EnvelopeOuterClass.ContentType

/**
 * Puts END_SESSION (content type 21) on the wire, then tears down the local session.
 *
 * **Canon:** iOS `MessagingServiceClient.sendEndSession` + `EndSessionPayload`.
 * Type 21 is unencrypted (the ratchet may be dead). Payload is a fixed 1024-byte
 * pad so length does not identify the frame. The reset *reason* is omitted until
 * Android has `sealToIdentity` FFI — a random pad is what a peer without a hint
 * already expects.
 *
 * Stealth: seal with `SealedInner.content_type = SESSION_RESET`. No identity key
 * → skip the RPC (fail-closed) and still archive locally.
 */
@Singleton
class SessionControlUseCase @Inject constructor(
    private val keystoreManager: KeystoreManager,
    private val sessionManager: SessionManager,
    private val sessionStateStore: SessionStateStore,
    private val userDao: UserDao,
    private val messagingService: MessagingService,
    private val stealthPolicy: StealthPolicy,
    private val stealthSender: StealthSenderService,
) {
    private val random = SecureRandom()
    private val lastSentAt = mutableMapOf<String, Long>()

    suspend fun sendEndSession(contactId: String): Boolean {
        val now = System.currentTimeMillis()
        val last = lastSentAt[contactId] ?: 0L
        if (now - last < COOLDOWN_MS) {
            Log.i(TAG, "END_SESSION cooldown ${contactId.take(8)}…")
            return false
        }
        lastSentAt[contactId] = now

        val myId = keystoreManager.getUserId() ?: return false
        val payload = ByteArray(PADDED_SIZE).also { random.nextBytes(it) }
        val messageId = UUID.randomUUID().toString().lowercase()
        val timestampMs = System.currentTimeMillis()
        val stealthOn = stealthPolicy.shouldUseSealedSender()
        val identity = userDao.getById(contactId)?.identityPublic

        val result = try {
            if (stealthOn) {
                if (identity == null || identity.isEmpty()) {
                    Log.w(TAG, "END_SESSION stealth-on, no IK for ${contactId.take(8)}… — not identified-downgrading")
                    archiveLocal(contactId)
                    return false
                }
                val sealed = stealthSender.buildSealedInner(
                    recipientUserId = contactId,
                    recipientIdentityKey = identity,
                    encryptedPayload = payload,
                    contentType = ContentType.CONTENT_TYPE_SESSION_RESET,
                )
                if (MessagingService.SEALED_UNAUTHENTICATED_TRANSPORT) {
                    messagingService.sendSealedMessage(sealed)
                } else {
                    messagingService.sendMessage(
                        messageId = messageId,
                        senderId = myId,
                        recipientId = contactId,
                        conversationId = "",
                        encryptedPayload = ByteArray(0),
                        timestampMs = timestampMs,
                        contentType = ContentType.CONTENT_TYPE_UNSPECIFIED,
                        sealedInner = sealed,
                    )
                }
            } else {
                messagingService.sendMessage(
                    messageId = messageId,
                    senderId = myId,
                    recipientId = contactId,
                    conversationId = "",
                    encryptedPayload = payload,
                    timestampMs = timestampMs,
                    contentType = ContentType.CONTENT_TYPE_SESSION_RESET,
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "END_SESSION RPC failed ${contactId.take(8)}…", e)
            archiveLocal(contactId)
            return false
        }

        if (!result.success) {
            Log.w(TAG, "END_SESSION rejected ${result.errorCode}")
        }
        archiveLocal(contactId)
        return result.success
    }

    suspend fun inboundEndSession(contactId: String) {
        Log.i(TAG, "inbound END_SESSION from ${contactId.take(8)}… — archive local, no bounce")
        archiveLocal(contactId)
    }

    private suspend fun archiveLocal(contactId: String) {
        runCatching { sessionManager.removeSession(contactId) }
        runCatching { sessionStateStore.removeSession("session:$contactId") }
        runCatching { sessionStateStore.removeMeta(contactId) }
    }

    private companion object {
        const val TAG = "SessionControl"
        const val PADDED_SIZE = 1024
        const val COOLDOWN_MS = 30_000L
    }
}
