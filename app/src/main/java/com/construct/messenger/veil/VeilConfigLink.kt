package com.construct.messenger.veil

import com.google.crypto.tink.subtle.Ed25519Verify
import java.net.URLDecoder
import java.security.GeneralSecurityException
import java.util.Base64
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * `konstruct://veil-config?d=<blob>` — a front handed over outside the server: a link or its QR,
 * from whoever issued it (`make-config-link` in construct-veil). The blob is base64url JSON
 * `{capability, exp, relay, sni, spki, signature}`, signed with Ed25519 by the relay-config key
 * over the same JSON without `signature`, keys sorted, compact, slashes not escaped — serde_json
 * of a sorted map, as the signer writes it.
 *
 * Two signatures stand behind an accepted link: the blob's over the coordinates, and the issuer's
 * over the capability inside, which the front checks again on every connection. A link that
 * fails either is refused whole. **Canon:** iOS `VeilConfigImporter.parseAndVerify` /
 * `importBlob`; decision `no-bundled-veil-fronts.md`.
 */
object VeilConfigLink {

    data class Config(
        val front: LearnedFront,
        /** Base64 (standard) of the capability blob, as `veil_start` takes it. */
        val capabilityB64: String,
        /** When the capability stops being accepted, Unix seconds; 0 = no expiry encoded. */
        val capabilityNotAfter: Long,
    )

    enum class Refusal { MALFORMED, BAD_SIGNATURE, EXPIRED, BAD_CAPABILITY }

    class Refused(val reason: Refusal, detail: String) : Exception("${reason.name.lowercase()}: $detail")

    /** The `d` value of a veil-config link, or null when [text] is not one. Host or first path segment. */
    fun blobOf(text: String): String? {
        val trimmed = text.trim()
        val scheme = "konstruct:"
        if (!trimmed.startsWith(scheme, ignoreCase = true)) return null
        val rest = trimmed.substring(scheme.length).trimStart('/')
        val queryAt = rest.indexOf('?')
        if (queryAt < 0) return null
        val marker = rest.substring(0, queryAt).trimEnd('/')
        if (!marker.equals("veil-config", ignoreCase = true)) return null
        return rest.substring(queryAt + 1).split('&')
            .firstOrNull { it.startsWith("d=") }
            ?.substring(2)
            ?.let { runCatching { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }.getOrNull() }
            ?.takeIf { it.isNotEmpty() }
    }

    fun isLink(text: String): Boolean = blobOf(text) != null

    /**
     * The verified front and capability in [blobB64Url], or [Refused]. [signingKeyHex] is the
     * relay-config key, which also issues capabilities; injectable for tests only.
     */
    fun parseAndVerify(
        blobB64Url: String,
        signingKeyHex: String = VeilCapabilityBlob.ISSUER_KEY_HEX,
        nowSeconds: Long = System.currentTimeMillis() / 1000,
    ): Config {
        val json = runCatching { String(Base64.getUrlDecoder().decode(blobB64Url), Charsets.UTF_8) }
            .getOrNull() ?: throw Refused(Refusal.MALFORMED, "not base64url")
        val obj = try {
            JSONObject(json)
        } catch (_: JSONException) {
            throw Refused(Refusal.MALFORMED, "not a JSON object")
        }
        if (!verifySignature(obj, VeilCapabilityBlob.hex(signingKeyHex))) {
            throw Refused(Refusal.BAD_SIGNATURE, "the blob's signature does not verify")
        }
        val relay = obj.optString("relay")
        val spki = obj.optString("spki")
        // `ticket` is what blobs carried before `capability`; iOS still reads it.
        val capability = obj.optString("capability").ifEmpty { obj.optString("ticket") }
        if (relay.isEmpty() || spki.isEmpty() || capability.isEmpty()) {
            throw Refused(Refusal.MALFORMED, "relay, spki or capability missing")
        }
        if (obj.has("exp")) {
            val exp = obj.optLong("exp", Long.MIN_VALUE)
            if (exp < nowSeconds) throw Refused(Refusal.EXPIRED, "the link expired at $exp")
        }
        val front = VeilFrontRules.normalize(relay, obj.optString("sni"), spki, learnedAtMs = nowSeconds * 1000)
            ?: throw Refused(Refusal.MALFORMED, "not a front: address or pin")
        val capabilityBytes = runCatching { Base64.getDecoder().decode(capability) }.getOrNull()
            ?: throw Refused(Refusal.MALFORMED, "capability is not base64")
        // A bearer capability: what `make-config-link` issues and what `veil_start` takes from a
        // link. A key-bound one is issued to this device over the tunnel, never handed over.
        val bearer = try {
            VeilCapabilityBlob.parseBearer(capabilityBytes, VeilCapabilityBlob.hex(signingKeyHex), nowSeconds)
        } catch (e: VeilCapabilityBlob.Invalid) {
            throw Refused(Refusal.BAD_CAPABILITY, e.message.orEmpty())
        }
        return Config(front, capability, bearer.notAfter)
    }

    private fun verifySignature(obj: JSONObject, key: ByteArray): Boolean {
        val field = obj.optString("signature")
        if (!field.startsWith(SIG_PREFIX)) return false
        val signature = runCatching { Base64.getUrlDecoder().decode(field.substring(SIG_PREFIX.length)) }.getOrNull() ?: return false
        val unsigned = JSONObject(obj.toString()).apply { remove("signature") }
        val canonical = canonical(unsigned) ?: return false
        return try {
            Ed25519Verify(key).verify(signature, canonical.toByteArray(Charsets.UTF_8))
            true
        } catch (_: GeneralSecurityException) {
            false
        }
    }

    /**
     * serde_json's compact form of [value] with keys sorted — what the signer signs. Null for a
     * value it cannot reproduce exactly (a fractional number): such a blob is refused, not guessed.
     */
    internal fun canonical(value: Any?): String? = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().sorted().toList()
            .map { key -> "${quote(key)}:${canonical(value.get(key)) ?: return null}" }
            .joinToString(",", "{", "}")
        is JSONArray -> (0 until value.length()).map { canonical(value.get(it)) ?: return null }.joinToString(",", "[", "]")
        is String -> quote(value)
        is Boolean -> value.toString()
        is Int, is Long -> value.toString()
        is java.math.BigInteger -> value.toString()
        else -> null
    }

    private fun quote(s: String): String = buildString {
        append('"')
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }

    private const val SIG_PREFIX = "ed25519:"
}
