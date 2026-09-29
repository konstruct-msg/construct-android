package com.construct.messenger.veil

import android.util.Base64
import com.construct.messenger.data.api.GrpcClient
import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.diagnostics.Log
import javax.inject.Inject
import javax.inject.Singleton
import shared.proto.services.v1.VeilServiceOuterClass.IssueVeilCapabilityRequest
import shared.proto.services.v1.VeilServiceOuterClass.IssueVeilCapabilityResponse

/** A front this client may dial: its address, the TLS name it presents, and its pinned key. */
data class VeilRelay(val address: String, val sni: String, val spkiHex: String)

/**
 * The bundled fronts. **Canon:** iOS `VEILConfig.seedRelays` — same address, SNI and pin; a
 * rotated relay cert changes both files. Trusted because they ship inside the signed app.
 * Android learns no other front yet: the signed manifest and `alternates` are iOS-only so far,
 * and without them nothing here needs a signature check.
 */
object VeilSeeds {
    val relays: List<VeilRelay> = listOf(
        VeilRelay(
            address = "api.divany-kresla.uk:443",
            sni = "api.divany-kresla.uk",
            spkiHex = "5621e47a745614de08efb054b01388f3bcf32c763ecf5f0aeaeb6b0785ff6861",
        ),
    )
}

/**
 * The per-user veil-front capability (B2, bearer): the relay's proof that this account may use
 * it. Issued by `VeilService.IssueVeilCapability` over whatever path works — direct, before the
 * network is censored, or the tunnel itself when renewing. **Canon:** iOS
 * `VeilCapabilityProvisioner`; decision `veil-ticket-provisioning-system.md`.
 *
 * Key-bound B1 (AUTH v3) is not done here yet: it needs the device's own Ed25519 `veil_sk`.
 */
@Singleton
class VeilCapabilities @Inject constructor(
    private val keystoreManager: KeystoreManager,
    private val grpcClient: GrpcClient,
) {
    /** The stored capability for [relay], base64, if it is still good for [RENEW_MARGIN_SECONDS]. */
    fun current(relay: VeilRelay, nowSeconds: Long = System.currentTimeMillis() / 1000): String? =
        keystoreManager.veilCapability(relay.address)
            ?.takeIf { (_, notAfter) -> notAfter - RENEW_MARGIN_SECONDS > nowSeconds }
            ?.first

    /**
     * A usable capability for [relay]: the stored one, or a fresh issue. Null when none could be
     * had — the caller does not start the tunnel, since veil-front refuses without one.
     */
    suspend fun ensure(relay: VeilRelay): String? {
        current(relay)?.let { return it }
        val response = runCatching {
            grpcClient.veil.issueVeilCapability(
                IssueVeilCapabilityRequest.newBuilder().setRelayAddress(relay.address).build(),
            )
        }.onFailure { Log.w(TAG, "capability issue for ${relay.address} failed", it) }.getOrNull() ?: return null

        val rejection = rejectionOf(relay, response)
        if (rejection != null) {
            Log.e(TAG, "capability for ${relay.address} refused — $rejection; nothing stored")
            return null
        }
        val blob = Base64.encodeToString(response.capability.toByteArray(), Base64.NO_WRAP)
        keystoreManager.saveVeilCapability(relay.address, blob, response.notAfter)
        Log.i(TAG, "capability stored for ${relay.address}, expires ${response.notAfter}")
        return blob
    }

    companion object {
        private const val TAG = "VeilCapabilities"

        /** Renewed a day ahead: a device offline for a while must not arrive with an expired one. */
        const val RENEW_MARGIN_SECONDS = 24 * 60 * 60L

        /**
         * Why [response] must not be stored for [relay], or null. The server may name the relay
         * it answers for, and pins travel with it; a front this app does not ship is refused
         * rather than learned, as is a pin other than the bundled one — the answer arrived over
         * a path the censor may sit on. Canon: the pin half of iOS `VeilRelayTrust`.
         */
        internal fun rejectionOf(relay: VeilRelay, response: IssueVeilCapabilityResponse): String? = when {
            response.capability.isEmpty -> "empty capability"
            response.capabilityVersion != 1 -> "capability_version ${response.capabilityVersion}, B2 expected"
            response.relayAddress.isNotEmpty() && response.relayAddress != relay.address ->
                "answered for ${response.relayAddress}"
            response.spki.isNotEmpty() && !response.spki.equals(relay.spkiHex, ignoreCase = true) ->
                "pin ${response.spki.take(12)}… is not the bundled one"
            response.sni.isNotEmpty() && response.sni != relay.sni -> "SNI ${response.sni}"
            else -> null
        }
    }
}
