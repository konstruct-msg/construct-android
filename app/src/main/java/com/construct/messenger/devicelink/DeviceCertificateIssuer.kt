package com.construct.messenger.devicelink

import com.construct.messenger.crypto.CryptoManager
import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.data.repository.DevicesRepository
import com.construct.messenger.data.repository.DevicesRepository.LinkResult
import com.construct.messenger.diagnostics.Log
import com.construct.messenger.security.PeerDeviceVetting
import com.construct.messenger.service.SessionManager
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import uniffi.construct_core.DeviceCertException
import uniffi.construct_core.DeviceLinkEvidence

/**
 * The account device's half of a link: vouch for the new device once its keys are in the
 * directory (`decisions/devices-of-an-account-sign-each-other.md`). **Canon:** iOS
 * `DeviceCertificateIssuer`.
 *
 * The certificate cannot be issued at approval: in both flows the new device's hybrid key and its
 * binding reach the directory only with its first prekey upload after the link, so this waits for
 * them, asks the core, publishes. The core signs only keys the QR named; a directory serving other
 * keys gets `EvidenceMismatch`, logged as what it is and never retried.
 *
 * Pending work lives in memory: a link is something the owner watches happen, and an app killed in
 * those minutes leaves the device unvouched — its peers then see the alarm, the safe side.
 */
@Singleton
class DeviceCertificateIssuer internal constructor(
    private val devices: DevicesRepository,
    private val sessionManager: SessionManager,
    private val cryptoManager: CryptoManager,
    private val keystoreManager: KeystoreManager,
    private val scope: CoroutineScope,
) {
    @Inject constructor(
        devices: DevicesRepository,
        sessionManager: SessionManager,
        cryptoManager: CryptoManager,
        keystoreManager: KeystoreManager,
    ) : this(devices, sessionManager, cryptoManager, keystoreManager, CoroutineScope(SupervisorJob() + Dispatchers.IO))

    private val jobs = ConcurrentHashMap<String, Job>()

    /** Flow A, after our QR was shown: wait for the device that uses [token], then vouch for it
     * with the proof it sent under [secret]. */
    fun awaitLink(token: String, secret: ByteArray) = start("token:$token") { awaitLinkNow(token, secret) }

    /** Flow B, after approving a join request whose QR carried `fp`: vouch for [deviceId]. */
    fun vouch(deviceId: String, fingerprint: ByteArray) =
        start("device:$deviceId") { vouchNow(deviceId, DeviceLinkEvidence.QrFingerprint(fingerprint)) }

    private fun start(key: String, body: suspend () -> Unit) {
        jobs.remove(key)?.cancel()
        jobs[key] = scope.launch {
            withTimeoutOrNull(KEYS_DEADLINE_MS) { body() }
                ?: Log.i(TAG, "${key.take(14)}… keys not published in time — not vouched")
            jobs.remove(key)
        }
    }

    internal suspend fun awaitLinkNow(token: String, secret: ByteArray) {
        while (true) {
            delay(POLL_MS)
            val result = try {
                devices.linkResult(token)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "link result: ${e.message}")
                continue
            }
            when (result) {
                LinkResult.Pending -> continue
                LinkResult.Expired -> return
                is LinkResult.Confirmed -> {
                    if (result.proof.isEmpty()) {
                        // A new device older than certificates: nothing proves its keys.
                        Log.i(TAG, "device=${result.deviceId.take(8)}… no proof of keys — not vouched")
                        return
                    }
                    vouchNow(result.deviceId, DeviceLinkEvidence.QrSecret(secret, result.proof))
                    return
                }
            }
        }
    }

    internal suspend fun vouchNow(deviceId: String, evidence: DeviceLinkEvidence) {
        val ownUserId = keystoreManager.getUserId() ?: return
        while (true) {
            val subject = try {
                sessionManager.discoverOwnDeviceBundles(ownUserId)
                    .firstOrNull { it.deviceId == deviceId }
                    ?.let(PeerDeviceVetting::subjectKeys)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "fetch ${deviceId.take(8)}…: ${e.message}")
                null
            }
            if (subject == null) {
                // Not published yet: the new device uploads its hybrid key after the link.
                delay(POLL_MS)
                continue
            }
            val certificate = try {
                cryptoManager.issueDeviceCertificate(subject, evidence)
            } catch (e: DeviceCertException.EvidenceMismatch) {
                // The directory serves keys other than the ones the QR showed: the substitution
                // the certificate exists to catch. Never signed, never retried.
                Log.e(TAG, "FAULT device=${deviceId.take(8)}… directory keys differ from the link QR — refused")
                return
            } catch (e: Exception) {
                Log.e(TAG, "issue ${deviceId.take(8)}…: ${e.message}")
                return
            }
            try {
                devices.publishCertificate(deviceId, certificate)
                Log.i(TAG, "device=${deviceId.take(8)}… vouched")
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "publish ${deviceId.take(8)}…: ${e.message}")
                delay(POLL_MS)
            }
        }
    }

    internal companion object {
        const val TAG = "DeviceCert"
        /** How long the new device has to publish its keys, and how often the server is asked. */
        const val KEYS_DEADLINE_MS = 15 * 60_000L
        const val POLL_MS = 5_000L
    }
}
