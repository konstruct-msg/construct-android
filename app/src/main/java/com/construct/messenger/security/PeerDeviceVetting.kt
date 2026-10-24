package com.construct.messenger.security

import com.construct.messenger.crypto.CryptoManager
import com.construct.messenger.data.local.ContactStore
import com.construct.messenger.data.model.SecurityNotice
import com.construct.messenger.diagnostics.Log
import com.construct.messenger.service.SessionManager.PeerBundle
import javax.inject.Inject
import javax.inject.Singleton
import uniffi.construct_core.DeviceCertKeys
import uniffi.construct_core.OfferedPeerDevice
import uniffi.construct_core.PeerDeviceVerdict

/**
 * Is every device of a contact's account vouched for by one we already trust?
 * `decisions/devices-of-an-account-sign-each-other.md`.
 *
 * The verdict is the core's (`judgePeerDevices`), the same on every client, so this only
 * translates — the account to the devices we trusted for it, the bundles to what the core takes —
 * and raises the alarm on what comes back. Only the alarm: an unvouched device still receives
 * copies (owner, 2026-10-23). **Canon:** iOS `PeerDeviceVetting`.
 */
@Singleton
class PeerDeviceVetting @Inject constructor(
    private val cryptoManager: CryptoManager,
    private val contacts: ContactStore,
    private val notices: SecurityNotices,
) {
    /**
     * Judge a full answer for [accountId] against the devices trusted **before** it was recorded —
     * after, every device it names is in the registry and none could ever be new.
     *
     * Only a full answer, and only a contact's: one narrowed to some device ids may leave out the
     * device that signed the others, and our own account is never the subject of a warning.
     */
    suspend fun vet(accountId: String, bundles: List<PeerBundle>, trustedBefore: List<String>) {
        val offered = offered(bundles)
        if (offered.isEmpty() || contacts.get(accountId) == null) return
        val verdicts = cryptoManager.judgePeerDevices(trustedBefore, offered) ?: return
        offered.zip(verdicts).forEach { (device, verdict) ->
            when (verdict) {
                PeerDeviceVerdict.KeyChanged ->
                    Log.e(TAG, "device=${device.deviceId.take(8)}… of ${accountId.take(8)}… presents another hybrid key")
                PeerDeviceVerdict.Invalid ->
                    Log.e(TAG, "device=${device.deviceId.take(8)}… of ${accountId.take(8)}… bundle does not hold together")
                else -> Unit
            }
        }
        val alarm = unvouched(trustedBefore, offered, verdicts)
        if (alarm.isEmpty()) return
        Log.e(TAG, "UNVOUCHED ${alarm.joinToString(",") { it.take(8) + "…" }} of ${accountId.take(8)}…")
        notices.raise(accountId, SecurityNotice.UNVOUCHED_DEVICE)
    }

    companion object {
        private const val TAG = "PeerDeviceVetting"

        /**
         * The devices of an answer the core should judge: those whose bundle carries the hybrid
         * key and its binding. A device without them cannot open a session with anyone (the core
         * requires PQ), so it can receive nothing and is not worth an alarm.
         */
        fun offered(bundles: List<PeerBundle>): List<OfferedPeerDevice> = bundles.mapNotNull { entry ->
            subjectKeys(entry)?.let { OfferedPeerDevice(entry.deviceId, it, entry.deviceCertificate) }
        }

        fun subjectKeys(entry: PeerBundle): DeviceCertKeys? {
            val b = entry.bundle
            val hybrid = b.hybridIdentityKey?.takeIf { it.isNotEmpty() } ?: return null
            val binding = b.hybridIdentitySignature?.takeIf { it.isNotEmpty() } ?: return null
            return DeviceCertKeys(b.identityPublic, b.verifyingKey, hybrid, binding)
        }

        /**
         * The devices to alarm about: unvouched, and not already trusted here — a device the user
         * was warned about once and kept is in the registry by then, and the next answer reads it
         * as pinned.
         */
        fun unvouched(trusted: List<String>, offered: List<OfferedPeerDevice>, verdicts: List<PeerDeviceVerdict>): List<String> {
            val known = trusted.toSet()
            return offered.zip(verdicts)
                .filter { (device, verdict) -> verdict == PeerDeviceVerdict.Unvouched && device.deviceId !in known }
                .map { (device, _) -> device.deviceId }
        }
    }
}
