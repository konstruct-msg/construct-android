package com.construct.messenger.domain.usecase

import android.util.Log
import com.construct.messenger.crypto.CryptoManager
import com.construct.messenger.data.api.MessagingService
import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.data.local.SessionStateStore
import com.construct.messenger.service.OrchestratorGateway
import com.construct.messenger.service.SessionManager
import com.construct.messenger.stealth.StealthPolicy
import com.construct.messenger.stealth.StealthSenderService
import java.security.SecureRandom
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import shared.proto.core.v1.EnvelopeOuterClass.ContentType
import uniffi.construct_core.CfeIncomingEvent
import uniffi.construct_core.TeardownAction

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
    private val messagingService: MessagingService,
    private val stealthPolicy: StealthPolicy,
    private val stealthSender: StealthSenderService,
    private val orchestrator: OrchestratorGateway,
    private val cryptoManager: CryptoManager,
) {
    private val random = SecureRandom()
    private val lastSentAt = mutableMapOf<String, Long>()

    suspend fun sendEndSessionToAll() {
        val liveDevices = sessionManager.liveContactIds()
        val liveByAccount = liveDevices
            .mapNotNull { deviceId ->
                sessionManager.accountIdForDevice(deviceId)?.let { accountId -> accountId to deviceId }
            }
            .groupBy({ it.first }, { it.second })
        for ((accountId, activeDevices) in liveByAccount) {
            val candidates = (sessionManager.knownDeviceIds(accountId) + activeDevices).distinct()
            val decisions = if (cryptoManager.isMessagingReady) {
                cryptoManager.planTeardown(candidates, peerOnDeadSession = false)
            } else {
                activeDevices.map { deviceId ->
                    uniffi.construct_core.TeardownDecision(deviceId, TeardownAction.SEND_AND_ARCHIVE)
                }
            }
            for (decision in decisions) {
                if (decision.action != TeardownAction.SKIP) {
                    sendEndSession(decision.deviceId, force = true)
                }
            }
        }
    }

    suspend fun sendEndSession(contactId: String, force: Boolean = false): Boolean {
        val target = sessionManager.resolveTarget(contactId) ?: run {
            Log.w(TAG, "END_SESSION skipped — no account/device mapping ${contactId.take(8)}…")
            return false
        }
        val deviceId = target.deviceId
        val accountId = target.accountId
        val now = System.currentTimeMillis()
        val last = lastSentAt[deviceId] ?: 0L
        if (!force && now - last < COOLDOWN_MS) {
            Log.i(TAG, "END_SESSION cooldown ${deviceId.take(8)}…")
            return false
        }
        lastSentAt[deviceId] = now

        val myId = keystoreManager.getUserId() ?: return false
        val payload = ByteArray(PADDED_SIZE).also { random.nextBytes(it) }
        val messageId = UUID.randomUUID().toString().lowercase()
        val timestampMs = System.currentTimeMillis()
        val stealthOn = stealthPolicy.shouldUseSealedSender()
        val identity = target.identityPublic

        val result = try {
            if (stealthOn) {
                if (identity.isEmpty()) {
                    Log.w(TAG, "END_SESSION stealth-on, no IK for ${deviceId.take(8)}… — not identified-downgrading")
                    archiveLocal(deviceId)
                    return false
                }
                val sealed = stealthSender.buildSealedInner(
                    recipientUserId = accountId,
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
                        recipientId = accountId,
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
                    recipientId = accountId,
                    conversationId = "",
                    encryptedPayload = payload,
                    timestampMs = timestampMs,
                    contentType = ContentType.CONTENT_TYPE_SESSION_RESET,
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "END_SESSION RPC failed ${deviceId.take(8)}…", e)
            archiveLocal(deviceId)
            return false
        }

        if (!result.success) {
            Log.w(TAG, "END_SESSION rejected ${result.errorCode}")
        }
        archiveLocal(deviceId)
        return result.success
    }

    /**
     * The peer tore down the ratchet with [deviceId]: drop it here, and tell the core, which keeps
     * the quiet that follows — our own teardown of the same ratchet is suppressed for its window,
     * so the heal a stale carrier raises next does not bounce an END_SESSION back.
     *
     * A device, never an account. Until 2026-09-27 this took the envelope's account id, and
     * `removeSession(account)` removed nothing: the core keys sessions by device. The dead ratchet
     * outlived every teardown, and the peer's re-init then failed on it and raised a heal — seen
     * on the Android↔iOS stand as an END_SESSION that destroyed the session the peer had just
     * opened. **Canon:** iOS `SessionCoordinator.messageRouter(_:receivedEndSession:)`.
     */
    suspend fun inboundEndSession(deviceId: String) {
        Log.i(TAG, "inbound END_SESSION from ${deviceId.take(8)}… — archive local, no bounce")
        archiveLocal(deviceId)
        runCatching { orchestrator.handleEvent(CfeIncomingEvent.PeerToreDown(deviceId)) }
            .onFailure { Log.w(TAG, "PeerToreDown ${deviceId.take(8)}… not reported", it) }
    }

    private suspend fun archiveLocal(contactId: String) {
        runCatching { sessionManager.removeSession(contactId) }
        runCatching {
            sessionStateStore.saveSecureStore(
                uniffi.construct_core.CfeSecureStoreSlot.Session(contactId),
                ByteArray(0),
            )
        }
        runCatching { sessionStateStore.removeMeta(contactId) }
    }

    private companion object {
        const val TAG = "SessionControl"
        const val PADDED_SIZE = 1024
        const val COOLDOWN_MS = 30_000L
    }
}
