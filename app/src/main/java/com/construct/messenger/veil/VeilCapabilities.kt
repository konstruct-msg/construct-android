package com.construct.messenger.veil

import android.util.Base64
import com.construct.messenger.data.api.GrpcClient
import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.diagnostics.Log
import com.google.protobuf.ByteString
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import shared.proto.services.v1.VeilServiceOuterClass.IssueVeilCapabilityRequest
import shared.proto.services.v1.VeilServiceOuterClass.IssueVeilCapabilityResponse

/**
 * The per-user veil-front capabilities: the relay's proof that this account may use it. Issued by
 * `VeilService.IssueVeilCapability` over whatever path works — direct, before the network is
 * censored, or the tunnel itself. **Canon:** iOS `VeilCapabilityProvisioner` (B2) and
 * `VeilCapabilityV2Bootstrapper` (B1); decision `veil-ticket-provisioning-system.md`.
 *
 * - **B2, bearer** — whoever holds the blob may use it. Issued on request with no key; the first
 *   tunnel is opened with it.
 * - **B1, key-bound** — issued to this device's `veil_pk` ([VeilAccessKey]) and usable only with
 *   its `veil_sk`, which never leaves the device. Requested over the tunnel once it is up, and
 *   preferred by `veil_start` whenever present; B2 stays the fallback, no flag day.
 */
@Singleton
class VeilCapabilities @Inject constructor(
    private val keystoreManager: KeystoreManager,
    private val grpcClient: GrpcClient,
    private val accessKey: VeilAccessKey,
) {
    /** The stored bearer capability for [relay], base64, if it is still good for [RENEW_MARGIN_SECONDS]. */
    fun current(relay: VeilRelay, nowSeconds: Long = System.currentTimeMillis() / 1000): String? =
        keystoreManager.veilCapability(relay.address)
            ?.takeIf { (_, notAfter) -> notAfter - RENEW_MARGIN_SECONDS > nowSeconds }
            ?.first

    /** The stored key-bound capability for [relay] and the seed it needs, if still good for [RENEW_MARGIN_SECONDS]. */
    fun currentKeyBound(relay: VeilRelay, nowSeconds: Long = System.currentTimeMillis() / 1000): KeyBound? =
        keystoreManager.veilKeyBoundCapability(relay.address)
            ?.takeIf { (_, notAfter) -> notAfter - RENEW_MARGIN_SECONDS > nowSeconds }
            ?.let { (blob, _) -> KeyBound(blob, accessKey.get().seedHex) }

    class KeyBound(val capabilityB64: String, val veilSkHex: String)

    /**
     * A usable bearer capability for [relay]: the stored one, or a fresh issue. Null when none
     * could be had — the caller does not start the tunnel, since veil-front refuses without one.
     */
    suspend fun ensure(relay: VeilRelay): String? {
        current(relay)?.let { return it }
        val response = issue(relay, veilPk = null) ?: return null
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

    private val keyBoundLock = Mutex()
    private var lastKeyBoundAttemptMs = 0L
    private var keyBoundEverIssued = false

    /**
     * Get or renew the key-bound capability for [relay]. Called once the tunnel is up, so the
     * request travels inside it; a no-op while the stored one has [KEY_BOUND_RENEW_WINDOW_SECONDS]
     * left, and rate-limited — the first attempt can lose to the route switch that follows a start.
     */
    suspend fun renewKeyBound(relay: VeilRelay, nowMs: Long = System.currentTimeMillis()) {
        if (!keyBoundLock.tryLock()) return
        try {
            val stored = keystoreManager.veilKeyBoundCapability(relay.address)
            if (stored != null && stored.second - KEY_BOUND_RENEW_WINDOW_SECONDS > nowMs / 1000) return
            val retry = if (keyBoundEverIssued) KEY_BOUND_RETRY_MS else KEY_BOUND_FIRST_RETRY_MS
            if (lastKeyBoundAttemptMs != 0L && nowMs - lastKeyBoundAttemptMs < retry) return
            lastKeyBoundAttemptMs = nowMs

            val veilPk = accessKey.get().publicKey
            val response = issue(relay, veilPk) ?: return
            val rejection = keyBoundRejectionOf(relay, response, veilPk)
            if (rejection != null) {
                Log.e(TAG, "key-bound capability for ${relay.address} refused — $rejection; nothing stored")
                return
            }
            val blob = Base64.encodeToString(response.capability.toByteArray(), Base64.NO_WRAP)
            keystoreManager.saveVeilKeyBoundCapability(relay.address, blob, response.notAfter)
            keyBoundEverIssued = true
            Log.i(TAG, "key-bound capability stored for ${relay.address}, expires ${response.notAfter}")
        } finally {
            keyBoundLock.unlock()
        }
    }

    private suspend fun issue(relay: VeilRelay, veilPk: ByteArray?): IssueVeilCapabilityResponse? = runCatching {
        grpcClient.veil.issueVeilCapability(
            IssueVeilCapabilityRequest.newBuilder()
                .setRelayAddress(relay.address)
                .apply { if (veilPk != null) setVeilPk(ByteString.copyFrom(veilPk)).setRole(ROLE_USER) }
                .build(),
        )
    }.onFailure { Log.w(TAG, "capability issue for ${relay.address} (key-bound: ${veilPk != null}) failed", it) }.getOrNull()

    companion object {
        private const val TAG = "VeilCapabilities"

        /** Renewed a day ahead: a device offline for a while must not arrive with an expired one. */
        const val RENEW_MARGIN_SECONDS = 24 * 60 * 60L

        /** Canon: iOS `VeilCapabilityV2Bootstrapper.renewWindow`. */
        const val KEY_BOUND_RENEW_WINDOW_SECONDS = 14 * 24 * 60 * 60L
        private const val KEY_BOUND_FIRST_RETRY_MS = 60_000L
        private const val KEY_BOUND_RETRY_MS = 60 * 60_000L

        /** `IssueVeilCapabilityRequest.role`: 0 = a user; 1 is a chained relay. */
        private const val ROLE_USER = 0

        /**
         * Why the bearer [response] must not be stored for [relay], or null. The server may name
         * the relay it answers for, and pins travel with it; an answer for another front is
         * refused rather than learned, as is a pin other than the one this front was learned with
         * ([VeilFrontStore]) — the answer arrived over a path the censor may sit on. Canon: the pin half of iOS `VeilRelayTrust`. The blob
         * must carry the issuer's signature, as the relay will require.
         */
        internal fun rejectionOf(
            relay: VeilRelay,
            response: IssueVeilCapabilityResponse,
            issuerKey: ByteArray = VeilCapabilityBlob.hex(VeilCapabilityBlob.ISSUER_KEY_HEX),
            nowSeconds: Long = System.currentTimeMillis() / 1000,
        ): String? {
            if (response.capabilityVersion != 1) return "capability_version ${response.capabilityVersion}, B2 expected"
            coordinatesRejection(relay, response)?.let { return it }
            return try {
                VeilCapabilityBlob.parseBearer(response.capability.toByteArray(), issuerKey, nowSeconds)
                null
            } catch (e: VeilCapabilityBlob.Invalid) {
                e.message
            }
        }

        /**
         * As [rejectionOf] for a key-bound answer, which must also be bound to [veilPk]: one bound
         * to any other key is useless here, and storing it would stop the renewal that fixes it.
         */
        internal fun keyBoundRejectionOf(
            relay: VeilRelay,
            response: IssueVeilCapabilityResponse,
            veilPk: ByteArray,
            issuerKey: ByteArray = VeilCapabilityBlob.hex(VeilCapabilityBlob.ISSUER_KEY_HEX),
            nowSeconds: Long = System.currentTimeMillis() / 1000,
        ): String? {
            if (response.capabilityVersion != 2) return "capability_version ${response.capabilityVersion}, B1 expected"
            coordinatesRejection(relay, response)?.let { return it }
            val parsed = try {
                VeilCapabilityBlob.parseKeyBound(response.capability.toByteArray(), issuerKey, nowSeconds)
            } catch (e: VeilCapabilityBlob.Invalid) {
                return e.message
            }
            return if (parsed.veilPk.contentEquals(veilPk)) null else "bound to another veil_pk"
        }

        private fun coordinatesRejection(relay: VeilRelay, response: IssueVeilCapabilityResponse): String? = when {
            response.capability.isEmpty -> "empty capability"
            response.relayAddress.isNotEmpty() && response.relayAddress != relay.address ->
                "answered for ${response.relayAddress}"
            response.spki.isNotEmpty() && !response.spki.equals(relay.spkiHex, ignoreCase = true) ->
                "pin ${response.spki.take(12)}… is not the pinned one"
            response.sni.isNotEmpty() && response.sni != relay.sni -> "SNI ${response.sni}"
            else -> null
        }
    }
}
