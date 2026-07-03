package com.construct.messenger.data.auth

import org.json.JSONObject
import java.util.Base64

/**
 * PASETO v4.public token utilities for Android.
 *
 * **Canon:** `docs/TOKEN_AUTH.md` §3.4 — last-resort userId recovery and format guard.
 *
 * # Design constraints
 *
 * - **No signature verification.** The server verifies signatures; the client trusts tokens
 *   it received over TLS from `AuthService`. Shipping the server's Ed25519 key in the APK
 *   would be a deployment liability with no security benefit.
 * - **`org.json.JSONObject`** is used (Android SDK built-in) instead of a heavyweight JSON
 *   library — this is a cold-path utility (app launch, token load), not a hot-path parser.
 * - **`java.util.Base64`** over `android.util.Base64` because it works in both JVM unit tests
 *   and on-device (available since minSdk 26 / Android 8.0).
 */
object TokenUtils {

    private const val PASETO_V4_PUBLIC_PREFIX = "v4.public."

    /**
     * Returns `true` if [token] is a syntactically valid PASETO v4.public token.
     *
     * Checks only the prefix and minimum structure — does NOT verify the signature
     * or parse claims. Use this as the format guard on app launch (§3.5).
     */
    fun isValidFormat(token: String): Boolean {
        if (!token.startsWith(PASETO_V4_PUBLIC_PREFIX)) return false
        val stripped = token.removePrefix(PASETO_V4_PUBLIC_PREFIX)
        if (stripped.isEmpty()) return false
        // payload (required) separated from footer (optional) by '.'
        val payloadB64 = stripped.substringBefore('.')
        if (payloadB64.isEmpty()) return false
        val payload = base64UrlDecode(payloadB64) ?: return false
        // Minimum: nonce(32) + empty message(0) + signature(64) = 96 bytes
        if (payload.size < 96) return false
        return true
    }

    /**
     * Extracts the `sub` (user id) claim from a PASETO v4.public token **without**
     * verifying the cryptographic signature.
     *
     * Returns `null` if:
     * - The token is not `v4.public.*`
     * - The base64url payload cannot be decoded
     * - The decoded payload is too short to contain nonce(32) + message + signature(64)
     * - The message portion is not valid UTF-8 JSON
     * - The JSON object has no `sub` field or it is empty
     */
    fun extractUserId(token: String): String? {
        if (!token.startsWith(PASETO_V4_PUBLIC_PREFIX)) return null

        val stripped = token.removePrefix(PASETO_V4_PUBLIC_PREFIX)
        // Payload ends at the first '.' (footer is optional)
        val payloadB64 = stripped.substringBefore('.')
        val payload = base64UrlDecode(payloadB64) ?: return null

        // Structure: nonce(32) || message(variable) || signature(64)
        if (payload.size <= 32 + 64) return null
        val message = payload.copyOfRange(32, payload.size - 64)

        val claims = runCatching {
            JSONObject(String(message, Charsets.UTF_8))
        }.getOrNull() ?: return null

        return claims.optString("sub").takeIf { it.isNotEmpty() }
    }

    /**
     * Decodes a base64url-encoded string (RFC 4648 §5, no padding) into raw bytes.
     *
     * Uses [java.util.Base64.getUrlDecoder] which natively handles the URL-safe alphabet
     * (`-` and `_` in positions 62/63). Only adds `=` padding to satisfy the decoder's
     * multiple-of-4 requirement.
     *
     * Available on Android since API 26 (minSdk = 26 ✓).
     */
    private fun base64UrlDecode(input: String): ByteArray? {
        val padded = input.let { it + "=".repeat((4 - it.length % 4) % 4) }
        return try {
            Base64.getUrlDecoder().decode(padded)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
