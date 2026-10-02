package com.construct.messenger.veil

import android.util.Base64
import com.construct.messenger.data.api.GrpcClient
import com.construct.messenger.data.auth.AuthSessionManager
import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.diagnostics.Log
import com.google.crypto.tink.subtle.Ed25519Verify
import java.security.GeneralSecurityException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import org.json.JSONObject
import shared.proto.services.v1.VeilServiceOuterClass.IssueBootstrapVoucherRequest
import shared.proto.services.v1.VeilServiceOuterClass.IssueVeilCapabilityRequest

/**
 * A front offered by the server with its coordinates signed: the primary of an
 * `IssueVeilCapability` answer, or one of its `alternates`. The signature is Ed25519 by the
 * relay-config key over `{"exp","relay","sni","spki"}` — canonical as [VeilConfigLink] writes it —
 * and covers the coordinates only, never the capability, which carries its own issuer signature.
 * **Canon:** iOS `VeilEntryPointSignature` + `VeilRelayTrust.verifyAndLearn`;
 * `backend/VEIL_SIGNED_ENTRYPOINT_SPEC.md`.
 */
data class OfferedFront(
    val relay: String,
    val sni: String,
    val spki: String,
    val notAfter: Long,
    val signature: String,
    val capability: ByteArray,
    val capabilityVersion: Int,
)

object VeilEntryPoints {

    /** True when [signature] is a valid `ed25519:<base64url>` over the canonical tuple. */
    fun verifySignature(relay: String, sni: String, spki: String, exp: Long, signature: String, keyHex: String): Boolean {
        if (!signature.startsWith(PREFIX) || relay.isEmpty() || sni.isEmpty() || spki.isEmpty()) return false
        val sig = runCatching { java.util.Base64.getUrlDecoder().decode(signature.substring(PREFIX.length)) }.getOrNull() ?: return false
        val tuple = JSONObject().put("exp", exp).put("relay", relay).put("sni", sni).put("spki", spki)
        val canonical = VeilConfigLink.canonical(tuple) ?: return false
        return try {
            Ed25519Verify(VeilCapabilityBlob.hex(keyHex)).verify(sig, canonical.toByteArray(Charsets.UTF_8))
            true
        } catch (_: GeneralSecurityException) {
            false
        }
    }

    /**
     * The front to pin and the capability to keep, or why not. [known] is the pin already held
     * for this address: a signature never re-points a front this device knows — the server could
     * otherwise move it by signing a new pin — so a known front must arrive with its own pin.
     * Only a bearer capability: none of these requests sends a key.
     */
    fun accept(
        offer: OfferedFront,
        known: LearnedFront?,
        keyHex: String = VeilCapabilityBlob.ISSUER_KEY_HEX,
        nowSeconds: Long = System.currentTimeMillis() / 1000,
    ): Result<Pair<LearnedFront, Long>> {
        val front = VeilFrontRules.normalize(offer.relay, offer.sni, offer.spki, nowSeconds * 1000)
            ?: return Result.failure(Refused("not a front: address or pin"))
        if (known != null) {
            if (known.spki != front.spki) return Result.failure(Refused("pin differs from the one held"))
        } else {
            if (offer.notAfter <= nowSeconds) return Result.failure(Refused("signed coordinates expired"))
            if (!verifySignature(offer.relay, offer.sni, offer.spki, offer.notAfter, offer.signature, keyHex)) {
                return Result.failure(Refused("coordinates not signed by the relay-config key"))
            }
        }
        if (offer.capabilityVersion != 1) return Result.failure(Refused("capability_version ${offer.capabilityVersion}, B2 expected"))
        val bearer = try {
            VeilCapabilityBlob.parseBearer(offer.capability, VeilCapabilityBlob.hex(keyHex), nowSeconds)
        } catch (e: VeilCapabilityBlob.Invalid) {
            return Result.failure(Refused("capability: ${e.message}"))
        }
        return Result.success((known ?: front) to bearer.notAfter)
    }

    class Refused(reason: String) : Exception(reason)

    private const val PREFIX = "ed25519:"
}

/**
 * Asks the server for a front when this device knows none, over whatever path works now — direct,
 * or a VPN the user turned on. Nothing is bundled ([VeilFrontStore]), so this is how a device
 * that has been online once gets its way out before the direct path closes.
 *
 * 1. `IssueVeilCapability` naming no front: the server answers with the one it runs (accepted
 *    only while it runs exactly one) and any `alternates`, each with signed coordinates.
 * 2. Otherwise `IssueBootstrapVoucher`: a signed `konstruct://veil-config` link, redeemed like a
 *    scanned one. Off on servers that have not enabled vouchers.
 *
 * Whatever is learned passed the relay-config key's signature; the server's word alone is never
 * a front.
 */
@Singleton
class VeilFrontProvisioner @Inject constructor(
    private val grpcClient: GrpcClient,
    private val fronts: VeilFrontStore,
    private val keystore: KeystoreManager,
    private val importer: VeilConfigImporter,
    private val authSession: AuthSessionManager,
) {
    private val lock = Mutex()
    private var lastAttemptMs = 0L

    /** True when a front is known afterwards. Rate-limited: a failed ask is not repeated for [RETRY_MS]. */
    suspend fun ensureFront(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (fronts.preferred() != null) return true
        if (!lock.tryLock()) return false
        try {
            if (fronts.preferred() != null) return true
            if (lastAttemptMs != 0L && nowMs - lastAttemptMs < RETRY_MS) return false
            lastAttemptMs = nowMs
            authSession.ensureFresh()
            if (fromCapability() > 0) return true
            return fromVoucher()
        } finally {
            lock.unlock()
        }
    }

    /** How many fronts the answer taught. */
    private suspend fun fromCapability(): Int {
        val response = runCatching {
            grpcClient.veil.withDeadlineAfter(DEADLINE_S, TimeUnit.SECONDS)
                .issueVeilCapability(IssueVeilCapabilityRequest.getDefaultInstance())
        }.getOrElse {
            Log.w(TAG, "front request (capability) failed: ${it.message}")
            return 0
        }
        val offers = listOf(
            OfferedFront(
                response.relayAddress, response.sni, response.spki, response.notAfter, response.signature,
                response.capability.toByteArray(), response.capabilityVersion,
            ),
        ) + response.alternatesList.map {
            OfferedFront(it.relayAddress, it.sni, it.spki, it.notAfter, it.signature, it.capability.toByteArray(), it.capabilityVersion)
        }
        return offers.count(::learn)
    }

    private fun learn(offer: OfferedFront): Boolean {
        val accepted = VeilEntryPoints.accept(offer, fronts.entry(offer.relay)).getOrElse {
            Log.e(TAG, "offered front refused — ${it.message}")
            return false
        }
        val (front, notAfter) = accepted
        if (!fronts.save(front.address, front.sni, front.spki)) return false
        keystore.saveVeilCapability(front.address, Base64.encodeToString(offer.capability, Base64.NO_WRAP), notAfter)
        Log.i(TAG, "front learned from the server (signed coordinates)")
        return true
    }

    private suspend fun fromVoucher(): Boolean {
        val response = runCatching {
            grpcClient.veil.withDeadlineAfter(DEADLINE_S, TimeUnit.SECONDS)
                .issueBootstrapVoucher(IssueBootstrapVoucherRequest.getDefaultInstance())
        }.getOrElse {
            Log.w(TAG, "front request (voucher) failed: ${it.message}")
            return false
        }
        return importer.redeem(response.configUri) == VeilConfigImporter.Outcome.Imported
    }

    private companion object {
        const val TAG = "VEIL"
        const val DEADLINE_S = 20L
        const val RETRY_MS = 10 * 60_000L
    }
}
