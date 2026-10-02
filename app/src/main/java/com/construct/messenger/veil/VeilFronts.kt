package com.construct.messenger.veil

import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.diagnostics.Log
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** A front this client may dial: its address, the TLS name it presents, and its pinned key. */
data class VeilRelay(val address: String, val sni: String, val spkiHex: String)

/** One front learned from a signed config link. Newest first when there are several. */
data class LearnedFront(val address: String, val sni: String, val spki: String, val learnedAtMs: Long) {
    val relay: VeilRelay get() = VeilRelay(address, sni, spki)
}

/**
 * What makes a learned front well-formed, and how the set grows. Pure, so the rules are tested
 * without a keystore. **Canon:** iOS `VeilLearnedFrontCore`.
 */
object VeilFrontRules {
    /** A link hands over one front at a time; the limit keeps the store from growing without bound. */
    const val MAX_ENTRIES = 8

    /**
     * The tuple, lowercased and checked, or null if it is not one. Checked on the way in, since a
     * malformed pin in the store is a pin comparison that cannot fail usefully. An empty SNI is the
     * host of the address.
     */
    fun normalize(address: String, sni: String, spki: String, learnedAtMs: Long): LearnedFront? {
        val addr = address.trim().lowercase()
        val pin = spki.trim().lowercase()
        if (!isValidPin(pin)) return null
        val host = hostOf(addr) ?: return null
        val name = sni.trim().lowercase().ifEmpty { host }
        if (name.isEmpty()) return null
        return LearnedFront(addr, name, pin, learnedAtMs)
    }

    /** 64 ASCII hex characters: SHA-256 of the SubjectPublicKeyInfo. */
    fun isValidPin(pin: String): Boolean = pin.length == 64 && pin.all { it in '0'..'9' || it in 'a'..'f' }

    /** The host of `host:port` with a port in 1..65535, or null. IPv6 comes bracketed. */
    fun hostOf(address: String): String? {
        val colon = address.lastIndexOf(':')
        if (colon <= 0) return null
        val host = address.substring(0, colon)
        val port = address.substring(colon + 1).toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        return if (host.startsWith("[") && host.endsWith("]")) host.substring(1, host.length - 1) else host
    }

    /** [entry] replaces any front at its address; newest first; at most [limit]. */
    fun merge(existing: List<LearnedFront>, entry: LearnedFront, limit: Int = MAX_ENTRIES): List<LearnedFront> =
        (listOf(entry) + existing.filter { it.address != entry.address })
            .sortedByDescending { it.learnedAtMs }
            .take(maxOf(1, limit))
}

/**
 * The fronts this device may use. Nothing is bundled: a name inside the APK is known to anyone who
 * unpacks it, and the one front Android used to carry was retired for exactly that
 * (`decisions/no-bundled-veil-fronts.md`). A front arrives only as data signed by the
 * relay-config key — today a `konstruct://veil-config` link or its QR ([VeilConfigLink]).
 *
 * The store records what a verified path decided to trust and checks only well-formedness.
 * Kept in the encrypted preferences with the capabilities, but not wiped at sign-out: a front is
 * this device's way out of a censored network, not the account's. **Canon:** iOS
 * `VeilLearnedFrontStore`.
 */
@Singleton
class VeilFrontStore @Inject constructor(private val keystore: KeystoreManager) {

    /** Newest first. */
    @Synchronized
    fun all(): List<LearnedFront> = decode(keystore.veilLearnedFronts())

    /**
     * The front to dial: the newest learned. **Canon:** iOS `VeilRelaySelector`, where learned
     * fronts lead newest first; there is no other source on Android to order them against.
     */
    fun preferred(): VeilRelay? = all().firstOrNull()?.relay

    fun entry(address: String): LearnedFront? = address.trim().lowercase().let { key -> all().firstOrNull { it.address == key } }

    /** Pin a tuple that has already passed signature verification. False: malformed, nothing kept. */
    @Synchronized
    fun save(address: String, sni: String, spki: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val entry = VeilFrontRules.normalize(address, sni, spki, nowMs) ?: run {
            Log.e(TAG, "refusing malformed front coordinates")
            return false
        }
        val merged = VeilFrontRules.merge(all(), entry)
        keystore.saveVeilLearnedFronts(encode(merged))
        Log.i(TAG, "front pinned (${merged.size} known)")
        return true
    }

    @Synchronized
    fun remove(address: String) {
        val key = address.trim().lowercase()
        keystore.saveVeilLearnedFronts(encode(all().filter { it.address != key }))
    }

    private fun encode(fronts: List<LearnedFront>): String = JSONArray().apply {
        fronts.forEach {
            put(JSONObject().put("address", it.address).put("sni", it.sni).put("spki", it.spki).put("learned_at_ms", it.learnedAtMs))
        }
    }.toString()

    /** What does not read back as a well-formed front is dropped, never dialled unpinned. */
    private fun decode(stored: String?): List<LearnedFront> {
        if (stored.isNullOrEmpty()) return emptyList()
        return try {
            val array = JSONArray(stored)
            (0 until array.length()).mapNotNull { i ->
                val o = array.getJSONObject(i)
                VeilFrontRules.normalize(o.optString("address"), o.optString("sni"), o.optString("spki"), o.optLong("learned_at_ms"))
            }.sortedByDescending { it.learnedAtMs }
        } catch (_: JSONException) {
            emptyList()
        }
    }

    private companion object {
        const val TAG = "VEIL"
    }
}
