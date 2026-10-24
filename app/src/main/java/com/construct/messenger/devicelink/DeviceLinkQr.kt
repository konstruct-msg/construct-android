package com.construct.messenger.devicelink

import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64

/**
 * The two link QRs, byte for byte as iOS writes and reads them, so either platform links with
 * the other. **Canon:** iOS `DeviceLinkViewModel` (`generateLinkCode`, `generateJoinRequestQR`,
 * `extractHex32`). Debug builds only, as on iOS (`DeviceLinkOfferPolicy`).
 *
 * - Flow A, shown by the account's device: `konstruct://link?token=<token>&fp=<hex>&s=<hex>` —
 *   `fp` is `history_qr_fingerprint` of the showing device, `s` the link secret the new device
 *   proves its keys under (`decisions/devices-of-an-account-sign-each-other.md`); `s` never goes
 *   to the server.
 * - Flow B, shown by the new device: `konstruct://link-to-me?id=<deviceId>&pubkey=<base64>&fp=<hex>&name=<name>&platform=<platform>`
 *   — `fp` names the new device's keys, its hybrid key included; the account's device vouches
 *   for exactly those.
 *
 * Hex is the QR text boundary (32 bytes, 64 lowercase characters); a value of any other shape
 * reads as absent.
 */
object DeviceLinkQr {
    const val LINK_PREFIX = "konstruct://link?"
    const val JOIN_PREFIX = "konstruct://link-to-me"

    /** Flow A, scanned by the new device. */
    data class Offer(val token: String, val fingerprint: ByteArray?, val secret: ByteArray?)

    /** Flow B, scanned by the account's device. */
    data class JoinRequest(
        val deviceId: String,
        val identityPublic: ByteArray?,
        val fingerprint: ByteArray?,
        val name: String,
        val platform: String,
    )

    fun offer(token: String, fingerprint: ByteArray?, secret: ByteArray): String = buildString {
        append("konstruct://link?token=").append(token)
        fingerprint?.let { append("&fp=").append(hex(it)) }
        append("&s=").append(hex(secret))
    }

    fun joinRequest(deviceId: String, identityPublic: ByteArray, fingerprint: ByteArray, name: String, platform: String): String {
        val pubkey = encode(Base64.getEncoder().encodeToString(identityPublic), UNRESERVED)
        return "konstruct://link-to-me?id=$deviceId&pubkey=$pubkey&fp=${hex(fingerprint)}" +
            "&name=${encode(name, QUERY_ALLOWED)}&platform=$platform"
    }

    fun isLink(text: String): Boolean = text.startsWith(LINK_PREFIX) || text.startsWith(JOIN_PREFIX)

    fun parseOffer(text: String): Offer? {
        if (!text.startsWith(LINK_PREFIX)) return null
        val q = query(text)
        val token = q["token"]?.takeIf { it.isNotEmpty() } ?: return null
        return Offer(token, hex32(q["fp"]), hex32(q["s"]))
    }

    fun parseJoinRequest(text: String): JoinRequest? {
        if (!text.startsWith(JOIN_PREFIX)) return null
        val q = query(text)
        val id = q["id"]?.takeIf { it.isNotEmpty() } ?: return null
        val name = q["name"] ?: return null
        val platform = q["platform"] ?: return null
        val identity = q["pubkey"]?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
        return JoinRequest(id, identity, hex32(q["fp"]), name, platform)
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun hex32(value: String?): ByteArray? {
        if (value == null || value.length != 64) return null
        return runCatching { ByteArray(32) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() } }.getOrNull()
    }

    /** Query items percent-decoded once, `+` kept as itself — as `URLComponents.queryItems`. */
    private fun query(text: String): Map<String, String> {
        val q = text.substringAfter('?', "")
        if (q.isEmpty()) return emptyMap()
        return q.split('&').mapNotNull { item ->
            val name = item.substringBefore('=')
            if (name.isEmpty()) return@mapNotNull null
            val raw = item.substringAfter('=', "")
            // iOS decodes `name` a second time; a name with a literal `%` is the only case where that differs.
            name to (runCatching { URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8") }.getOrNull() ?: raw)
        }.reversed().toMap()
    }

    private fun encode(value: String, allowed: (Char) -> Boolean): String = buildString {
        value.codePoints().forEach { cp ->
            if (cp < 0x80 && allowed(cp.toChar())) append(cp.toChar())
            else append(URLEncoder.encode(String(Character.toChars(cp)), "UTF-8").replace("+", "%20"))
        }
    }

    /** iOS `CharacterSet.alphanumerics` + `-._~` — ASCII part, the only part a base64 string has. */
    private val UNRESERVED: (Char) -> Boolean = { it.isLetterOrDigit() || it in "-._~" }

    /**
     * iOS `CharacterSet.urlQueryAllowed`, ASCII, less `&`, `=` and `+`: iOS leaves those as they
     * are, so a name holding one splits its own query. Escaped, both platforms read it back.
     */
    private val QUERY_ALLOWED: (Char) -> Boolean = { it.isLetterOrDigit() || it in "-._~!$'()*,;:@/?" }
}
