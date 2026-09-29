package com.construct.messenger.veil

import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.diagnostics.Log
import com.construct.messenger.veil.VeilCapabilityBlob.toHex
import com.google.crypto.tink.subtle.Ed25519Sign
import javax.inject.Inject
import javax.inject.Singleton

/**
 * This device's veil-front access keypair (`veil_sk` / `veil_pk`, Ed25519), which a key-bound
 * capability (B1, AUTH v3) is issued to. Generated here on first use; only `veil_pk` leaves the
 * device, in `IssueVeilCapability`. `veil_sk` goes to `veil_start`, which signs the handshake with
 * it. **Canon:** iOS `VeilAccessKeyStore`; decision `veil-ticket-provisioning-system.md` (B1).
 *
 * Wiped with the account ([KeystoreManager.clearTokens]): a stable key shared by two accounts on
 * one device would let the issuer and the relay link them.
 */
@Singleton
class VeilAccessKey @Inject constructor(
    private val keystoreManager: KeystoreManager,
) {
    class Pair(val seed: ByteArray, val publicKey: ByteArray) {
        val seedHex: String get() = seed.toHex()
    }

    @Synchronized
    fun get(): Pair {
        keystoreManager.veilAccessSeed()?.let { seed ->
            return Pair(seed, Ed25519Sign.KeyPair.newKeyPairFromSeed(seed).publicKey)
        }
        val generated = Ed25519Sign.KeyPair.newKeyPair()
        keystoreManager.saveVeilAccessSeed(generated.privateKey)
        Log.i(TAG, "generated the veil access keypair")
        return Pair(generated.privateKey, generated.publicKey)
    }

    private companion object {
        const val TAG = "VEIL"
    }
}
