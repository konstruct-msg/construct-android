package com.construct.messenger.diagnostics

import java.io.File
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArraySet
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Keeps VEIL front names out of the log (TODO 135, vault `decisions/veil-front-in-logs-is-a-keyed-label.md`).
 *
 * A front's address in logcat, or in a log someone sends, is one more name to block; collected
 * from several testers it is all of them. Every line [Log] writes passes through [redact]: each
 * known front name — host and SNI, registered by the VEIL code as it learns them — and every IPv4
 * or IPv6 literal but loopback becomes `front#` (`ip#`) and eight hex characters of HMAC-SHA256 under a key that
 * is random per device. The same front reads the same in one device's logs, so a log still tells
 * a story; across devices labels do not match. A bare hash would not do: fronts are a short list,
 * and whoever holds the candidates would hash them and compare.
 *
 * Debug builds write names as they are ([install] with `plain`): reading the address off logcat
 * is how a front's traffic is captured and its ClientHello tuned. Before [install] — and in JVM
 * tests — the key is random per process, so nothing is ever written in the clear by default.
 */
object LogRedaction {
    @Volatile
    private var key: ByteArray = ByteArray(KEY_LEN).also { SecureRandom().nextBytes(it) }

    @Volatile
    private var plain = false

    private val names = CopyOnWriteArraySet<String>()

    /** Load this device's key, minting it on first run; [plain] writes names unchanged (debug builds). */
    fun install(keyFile: File, plain: Boolean) {
        this.plain = plain
        val stored = runCatching { keyFile.readBytes() }.getOrNull()?.takeIf { it.size == KEY_LEN }
        key = stored ?: ByteArray(KEY_LEN).also { SecureRandom().nextBytes(it) }.also { minted ->
            runCatching {
                keyFile.parentFile?.mkdirs()
                keyFile.writeBytes(minted)
            }
        }
    }

    /** A front's host or SNI, from here on written as its label. A `host:port` registers its host. */
    fun register(vararg name: String) {
        for (n in name) {
            val host = hostOf(n.trim().lowercase())
            if (host.length >= MIN_NAME && host != LOOPBACK) names += host
        }
    }

    /** How [name] (an address, a host, an SNI) appears in the log. */
    fun label(name: String): String = label(name, "front#")

    private fun label(name: String, prefix: String): String {
        if (plain) return name
        val host = hostOf(name.trim().lowercase())
        val mac = Mac.getInstance(HMAC).apply { init(SecretKeySpec(key, HMAC)) }.doFinal(host.toByteArray())
        return prefix + mac.take(LABEL_BYTES).joinToString("") { "%02x".format(it) }
    }

    /** [text] with every registered name and every non-loopback IP literal as its label. */
    fun redact(text: String): String {
        if (plain || text.isEmpty()) return text
        var out = text
        // Longest first, so a name inside a longer one does not split it.
        for (name in names.sortedByDescending { it.length }) {
            if (out.contains(name, ignoreCase = true)) out = out.replace(name, label(name), ignoreCase = true)
        }
        // An address a name resolved to, in a connect error, names the front as surely as the name.
        // IPv6 first: an IPv4-mapped address (`::ffff:1.2.3.4`) is then left to the IPv4 pass.
        out = IPV6.replace(out) { m -> if (m.value in IPV6_LOOPBACK) m.value else label(m.value, "ip#") }
        return IPV4.replace(out) { m -> if (m.value.startsWith("127.")) m.value else label(m.value, "ip#") }
    }

    /** [error] as it may be written: the same type and stack, every message along the chain redacted. */
    fun redact(error: Throwable): Throwable {
        if (plain) return error
        val changed = generateSequence(error) { it.cause }.take(MAX_CAUSES).any { e ->
            e.message?.let { redact(it) != it } == true
        }
        return if (changed) Redacted(error) else error
    }

    /** Stands in for an error whose messages named a front. */
    private class Redacted(original: Throwable, depth: Int = 0) : Throwable(
        "${original.javaClass.name}: ${redact(original.message.orEmpty())}",
        original.cause?.takeIf { depth < MAX_CAUSES }?.let { Redacted(it, depth + 1) },
    ) {
        init { stackTrace = original.stackTrace }

        override fun toString(): String = message.orEmpty()
    }

    private fun hostOf(address: String): String {
        if (address.startsWith("[")) return address.substringAfter('[').substringBefore(']')
        val colon = address.lastIndexOf(':')
        return if (colon > 0 && address.indexOf(':') == colon && address.substring(colon + 1).all(Char::isDigit)) {
            address.substring(0, colon)
        } else {
            address
        }
    }

    private val IPV4 = Regex("""(?<![\d.])(?:\d{1,3}\.){3}\d{1,3}(?![\d.])""")

    /**
     * Eight full groups, or groups around one `::`. Never fewer colons and no `::`: a time
     * (`12:34:56`) or a MAC (`aa:bb:cc:dd:ee:ff`) is not an address. Bracketed and zoned forms
     * match inside their brackets / before the `%`.
     */
    private val IPV6 = Regex(
        """(?<![\w:.])(?:(?:[0-9A-Fa-f]{1,4}:){7}[0-9A-Fa-f]{1,4}""" +
            """|(?:[0-9A-Fa-f]{1,4}(?::[0-9A-Fa-f]{1,4}){0,6})?::(?:[0-9A-Fa-f]{1,4}(?::[0-9A-Fa-f]{1,4}){0,6})?)(?![\w:.])""",
    )
    private val IPV6_LOOPBACK = setOf("::1", "::")
    private const val HMAC = "HmacSHA256"
    private const val KEY_LEN = 32
    private const val LABEL_BYTES = 4
    private const val MIN_NAME = 4
    private const val MAX_CAUSES = 8
    private const val LOOPBACK = "127.0.0.1"
}
