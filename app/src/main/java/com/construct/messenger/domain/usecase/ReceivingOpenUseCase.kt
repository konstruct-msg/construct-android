package com.construct.messenger.domain.usecase

import android.util.Log
import com.construct.messenger.crypto.CryptoManager
import com.construct.messenger.crypto.KyberPrekeyService
import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.data.local.SessionStateStore
import com.construct.messenger.service.SessionManager
import com.construct.messenger.stealth.StealthSenderService
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import uniffi.construct_core.CfeAction
import uniffi.construct_core.SenderCertificate

/**
 * Opens a receiving session from what the core holds queued for one device.
 *
 * A first contact or a new state over a session held, and nothing is fetched: each queued
 * message opens with the key its sender certificate names, once the core has checked the server's
 * signature (`decisions/first-message-opens-without-the-server.md`). Until 2026-09-27 this fetched
 * the sender account's bundles and walked them — which also told the server whom the sealed
 * message was from, and guessed the device when the message did not name one.
 *
 * Nothing is announced after the open: until 2026-09-27 a `session_ready` closed the initiator's
 * confirm window; the window is gone, and the peer's first reply is what tells it the session
 * opened (`decisions/sessions-renew-by-sending.md`).
 *
 * **Canon:** iOS `SessionCoordinator.openReceiving(_:site:certificate:)`.
 */
@Singleton
class ReceivingOpenUseCase @Inject constructor(
    private val cryptoManager: CryptoManager,
    private val sessionManager: SessionManager,
    private val stealthSender: StealthSenderService,
    private val sessionStateStore: SessionStateStore,
    private val keystoreManager: KeystoreManager,
    private val uploadPreKeys: UploadPreKeysUseCase,
    private val kyberPrekeys: KyberPrekeyService,
) {
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    sealed interface Outcome {
        /** A session exists with [device], opened from [openerMessageId]. [actions] — the save, the
         * opener's decrypt, whatever drained behind it, an archived session — must be executed. */
        data class Opened(val device: String, val openerMessageId: String, val actions: List<CfeAction>) : Outcome

        /** Nothing opened. [tried] were refused or proven unopenable; [dropped] is the rest of the
         * core's queue for the device, already given up — both are released, not retried. */
        data class Failed(
            val tried: List<String>,
            val dropped: List<String>,
            val lastError: String?,
            val actions: List<CfeAction>,
        ) : Outcome

        /** Not tried, or a certificate could not be checked yet (no server key). Nothing was
         * spent; the redelivery retries. */
        data object Unreachable : Outcome
    }

    /**
     * [certificate] is the triggering message's: on success it records the device and key, which
     * the sealed replies need at once.
     */
    suspend fun open(device: String, certificate: SenderCertificate?): Outcome {
        if (!inFlight.add(device)) {
            Log.i(TAG, "receiving open already in flight ${device.take(8)}…")
            return Outcome.Unreachable
        }
        return try {
            if (!cryptoManager.isMessagingReady) return Outcome.Unreachable
            val result = cryptoManager.openReceiving(device, stealthSender.trustedServerKeys())
            // First: the open burned a Kyber one-time key. Until this blob is stored, a restart
            // brings the key back and a replay of the message would open again.
            result.kyberPrekeys?.let { blob -> kyberPrekeys.persist(ByteArray(blob.size) { blob[it].toByte() }) }

            val opened = result.openedDevice
            val openerId = result.openerMessageId
            if (opened == null || openerId == null) {
                if (result.awaitingServerKey) {
                    Log.e(TAG, "open_receiving ${device.take(8)}… — no server key to check the sender certificate; the redelivery retries")
                    return Outcome.Unreachable
                }
                val reason = result.lastError.orEmpty()
                Log.i(
                    TAG,
                    "open_receiving_failed ${device.take(8)}… — ${result.triedMessageIds.size} tried, " +
                        "${result.droppedMessageIds.size} dropped; last: $reason",
                )
                return Outcome.Failed(result.triedMessageIds, result.droppedMessageIds, result.lastError, result.actions)
            }

            certificate?.takeIf { it.deviceId == opened }?.let { sessionManager.recordOpenedDevice(it) }
            // The open consumed an OTPK; drop its private from storage too.
            runCatching { uploadPreKeys.persistLocal() }
                .onFailure { Log.w(TAG, "OTPK persist after open failed", it) }
            keystoreManager.getDeviceId()?.let { deviceId ->
                runCatching { uploadPreKeys.replenishIfNeeded(deviceId) }
            }
            Log.i(TAG, "open_receiving ${opened.take(8)}… opened from ${openerId.take(8)}…")
            Outcome.Opened(opened, openerId, result.actions)
        } catch (e: Exception) {
            Log.e(TAG, "open_receiving ${device.take(8)}… threw", e)
            Outcome.Unreachable
        } finally {
            inFlight.remove(device)
        }
    }

    private companion object {
        const val TAG = "ReceivingOpen"
    }
}
