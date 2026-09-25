package com.construct.messenger.domain.usecase

import android.util.Log
import com.construct.messenger.crypto.CryptoManager
import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.data.local.SessionStateStore
import com.construct.messenger.data.model.IdentityIds
import com.construct.messenger.service.MessageRouter
import com.construct.messenger.service.OrchestratorGateway
import com.construct.messenger.service.SessionManager
import com.construct.messenger.util.IncomingPlaintext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import uniffi.construct_core.BinaryFirstMessage
import uniffi.construct_core.CfeAction
import uniffi.construct_core.CfeIncomingEvent
import uniffi.construct_core.CfeSecureStoreSlot
import uniffi.construct_core.ReceivingInitCarrier
import uniffi.construct_core.wirePayloadUnpack

/**
 * RESPONDER X3DH: fetch the sender's prekey bundle and `initReceivingSession`.
 *
 * **Canon:** iOS `PublicKeyBundleHandler.handlePublicKeyBundleForIncomingMessage`.
 * GetPreKeyBundle is destructive here (consume=true) — this *is* session init.
 */
@Singleton
class ResponderInitUseCase @Inject constructor(
    private val sessionManager: SessionManager,
    private val cryptoManager: CryptoManager,
    private val orchestrator: OrchestratorGateway,
    private val sessionStateStore: SessionStateStore,
    private val keystoreManager: KeystoreManager,
    private val uploadPreKeys: UploadPreKeysUseCase,
    private val sessionControl: SessionControlUseCase,
) {
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    data class Result(val contactId: String, val messageId: String, val plaintext: ByteArray)

    sealed interface Outcome {
        /** [drained] is what the core answered to `SessionInitCompleted`: the messages it held
         * behind this init, now decrypted, plus their side effects. They must be executed — saving
         * them is not enough, or every message that arrived during the init is lost. */
        data class Established(val result: Result, val drained: List<CfeAction>) : Outcome
        /** Every candidate refused this init. Re-delivery cannot change that — the carrier is
         * dead, and holding it keeps it first in line on every start. [sender] is the device the
         * init came from when it can be named; the caller asks the core whether to tear it down. */
        data class Failed(val sender: String?) : Outcome
        /** Not tried (another init in flight, core not ready, bundles unreachable) — a later
         * delivery may succeed. */
        data object NotAttempted : Outcome
    }

    suspend fun establish(
        incoming: MessageRouter.IncomingMessage,
        preferredDeviceId: String? = null,
    ): Outcome {
        val accountId = incoming.senderId
        if (!inFlight.add(accountId)) {
            Log.i(TAG, "init already in flight ${accountId.take(8)}…")
            return Outcome.NotAttempted
        }
        return try {
            if (!cryptoManager.isMessagingReady) return Outcome.NotAttempted
            val discovered = sessionManager.discoverPeerBundles(accountId)
            if (discovered.isEmpty()) return Outcome.NotAttempted
            val candidates = if (preferredDeviceId != null && IdentityIds.isCryptoDeviceId(preferredDeviceId)) {
                discovered.sortedBy { if (it.deviceId == preferredDeviceId) 0 else 1 }
            } else {
                discovered
            }
            val wire = wirePayloadUnpack(incoming.encryptedPayload.map { it.toUByte() })
            val carrier = ReceivingInitCarrier(
                messageNumber = wire.messageNumber,
                oneTimePrekeyId = wire.oneTimePrekeyId,
                kemCiphertextBytes = wire.kemCiphertext?.size?.toUInt() ?: 0u,
                pqMessageEpoch = wire.pqMessageEpoch,
                isSessionResetInit = incoming.contentType ==
                    shared.proto.core.v1.EnvelopeOuterClass.ContentType.CONTENT_TYPE_SESSION_RESET_INIT,
            )
            val first = BinaryFirstMessage(
                ephemeralPublicKey = wire.dhPublicKey,
                messageNumber = wire.messageNumber,
                content = wire.sealedBox,
                oneTimePrekeyId = wire.oneTimePrekeyId,
                suiteId = wire.suiteId,
                pqMessageEpoch = wire.pqMessageEpoch,
                pqRatchetField = wire.pqRatchetField,
                // `wire_payload_unpack` is the only reading of these. kyber_prekey_id on the
                // first message is the wire's kyber_otpk_id (0 when the handshake used the SPK).
                pqxdhV2 = wire.pqxdhV2,
                kyberPrekeyId = wire.kyberOtpkId,
                kemCiphertext = wire.kemCiphertext ?: emptyList(),
            )
            val attempts = cryptoManager.planReceivingInit(listOf(carrier), candidates.size)
            for (attempt in attempts) {
                val candidate = candidates.getOrNull(attempt.bundleIndex.toInt()) ?: continue
                val init = runCatching {
                    sessionManager.initReceivingSession(candidate.deviceId, candidate.bundle, first)
                }.onFailure {
                    Log.d(TAG, "candidate failed ${candidate.deviceId.take(8)}…", it)
                }.getOrNull() ?: continue

                val blob = cryptoManager.exportSessionBytes(candidate.deviceId)
                sessionStateStore.saveSecureStore(CfeSecureStoreSlot.Session(candidate.deviceId), blob)
                if (sessionStateStore.getEstablishedAt(candidate.deviceId) == null) {
                    sessionStateStore.setEstablishedAt(candidate.deviceId, System.currentTimeMillis())
                }
                val drained = runCatching {
                    orchestrator.handleEvent(
                        CfeIncomingEvent.SessionInitCompleted(candidate.deviceId, blob),
                    ).also { sessionStateStore.saveCfeActions(it) }
                }.onFailure { Log.e(TAG, "SessionInitCompleted failed ${candidate.deviceId.take(8)}…", it) }
                    .getOrDefault(emptyList())
                // The init consumed an OTPK; drop its private from storage too.
                runCatching { uploadPreKeys.persistLocal() }
                    .onFailure { Log.w(TAG, "OTPK persist after init failed", it) }
                keystoreManager.getDeviceId()?.let { deviceId ->
                    runCatching { uploadPreKeys.replenishIfNeeded(deviceId) }
                }
                runCatching { sessionControl.sendReady(candidate.deviceId) }
                    .onFailure { Log.w(TAG, "session_ready failed ${candidate.deviceId.take(8)}…", it) }
                val plaintext = init.decryptedMessage.map { it.toByte() }.toByteArray()
                Log.i(
                    TAG,
                    "RESPONDER session for ${candidate.deviceId.take(8)}… " +
                        "attempt=${attempt.bundleIndex + 1u}/${candidates.size} " +
                        "knst=${IncomingPlaintext.isKnst(plaintext)}",
                )
                return Outcome.Established(Result(candidate.deviceId, incoming.messageId, plaintext), drained)
            }
            Log.w(TAG, "RESPONDER candidates exhausted for ${accountId.take(8)}…")
            // Named only when certain — the preferred id, or the only candidate. A guess would
            // tear down the peer's other, healthy devices.
            val sender = preferredDeviceId?.takeIf { id -> candidates.any { it.deviceId == id } }
                ?: candidates.singleOrNull()?.deviceId
            Outcome.Failed(sender)
        } catch (e: Exception) {
            Log.e(TAG, "RESPONDER init failed ${accountId.take(8)}…", e)
            Outcome.NotAttempted
        } finally {
            inFlight.remove(accountId)
        }
    }

    private companion object {
        const val TAG = "ResponderInit"
    }
}
