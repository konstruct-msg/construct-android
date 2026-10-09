package com.construct.messenger.veil

import com.construct.messenger.R
import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.diagnostics.Log
import com.construct.messenger.transport.TransportEvents
import com.construct.messenger.transport.TransportRoute
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Redeems a `konstruct://veil-config` link, tapped, scanned or pasted (a pasted blob without the
 * link around it too): verify it ([VeilConfigLink]), pin its front, keep its capability, and let
 * the route pick the front up. **Canon:** iOS `VeilConfigImporter.importScannedOrPasted` +
 * `VeilVoucherRedemption` — except a bare capability, which iOS pairs with a bundled front and
 * Android has none to pair it with (`decisions/no-bundled-veil-fronts.md`).
 */
@Singleton
class VeilConfigImporter @Inject constructor(
    private val fronts: VeilFrontStore,
    private val keystore: KeystoreManager,
    private val events: TransportEvents,
) {
    sealed interface Outcome {
        data object Imported : Outcome
        data class Refused(val reason: VeilConfigLink.Refusal) : Outcome
    }

    /** [signingKeyHex] and [nowSeconds] are for tests: they cannot sign with the real key. */
    fun redeem(
        text: String,
        signingKeyHex: String = VeilCapabilityBlob.ISSUER_KEY_HEX,
        nowSeconds: Long = System.currentTimeMillis() / 1000,
    ): Outcome {
        val blob = VeilConfigLink.blobOfPasted(text) ?: return Outcome.Refused(VeilConfigLink.Refusal.MALFORMED)
        val config = try {
            VeilConfigLink.parseAndVerify(blob, signingKeyHex, nowSeconds)
        } catch (e: VeilConfigLink.Refused) {
            Log.e(TAG, "veil-config refused — ${e.message}")
            return Outcome.Refused(e.reason)
        }
        val front = config.front
        if (!fronts.save(front.address, front.sni, front.spki, nowSeconds * 1000)) return Outcome.Refused(VeilConfigLink.Refusal.MALFORMED)
        // 0 = no expiry encoded; the stored expiry is what decides whether to ask for a new one.
        val notAfter = config.capabilityNotAfter.takeIf { it != 0L } ?: Long.MAX_VALUE / 2
        keystore.saveVeilCapability(front.address, config.capabilityB64, notAfter)
        Log.i(TAG, "veil-config imported: a front and its capability")
        // A proxy that is up moves to the new front; otherwise the next start dials it. The mode
        // is the user's — a link does not turn VEIL on.
        events.post(TransportRoute.Event.VeilConfigChanged)
        return Outcome.Imported
    }

    companion object {
        private const val TAG = "VEIL"

        /** What to tell the user. Never the front's address: the screen is a public surface. */
        fun messageOf(outcome: Outcome): Int = when (outcome) {
            Outcome.Imported -> R.string.veil_config_import_ok
            is Outcome.Refused -> when (outcome.reason) {
                VeilConfigLink.Refusal.BAD_SIGNATURE, VeilConfigLink.Refusal.BAD_CAPABILITY -> R.string.veil_import_err_signature
                VeilConfigLink.Refusal.EXPIRED -> R.string.veil_import_err_expired
                VeilConfigLink.Refusal.MALFORMED -> R.string.veil_import_err_malformed
            }
        }
    }
}
