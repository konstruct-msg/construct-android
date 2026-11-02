package com.construct.messenger.veil

import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.diagnostics.Log
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A random 32-byte device key that keys the scores fingerprint hash (HMAC-SHA256) inside
 * construct-veil, so the SQLite scores DB stores opaque blobs rather than a readable SSID/BSSID
 * history. Generated once and kept in the Keystore-backed store; it never leaves the device and
 * survives logout (the per-network score counts are device-level). **Canon:** iOS
 * `VeilScoringKeyStore`; construct-docs TODO 214.
 */
@Singleton
class VeilScoringKey @Inject constructor(
    private val keystoreManager: KeystoreManager,
) {
    @Synchronized
    fun get(): ByteArray {
        keystoreManager.veilScoresKey()?.takeIf { it.size == 32 }?.let { return it }
        val generated = ByteArray(32).also { SecureRandom().nextBytes(it) }
        keystoreManager.saveVeilScoresKey(generated)
        Log.i(TAG, "generated the veil scores-fingerprint key")
        return generated
    }

    private companion object {
        const val TAG = "VEIL"
    }
}
