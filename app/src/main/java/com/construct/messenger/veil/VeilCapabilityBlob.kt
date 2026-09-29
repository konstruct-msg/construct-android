package com.construct.messenger.veil

import com.google.crypto.tink.subtle.Ed25519Verify
import java.security.GeneralSecurityException

/**
 * The two capability layouts `IssueVeilCapability` returns, parsed and checked the way the relay
 * checks them: the issuer's Ed25519 signature and the validity window. A capability that fails
 * here would be refused by the front anyway; checking before storing keeps a forged or truncated
 * one from occupying the slot a good one would renew into.
 *
 * **Canon:** iOS `VeilConfigImporter.parseCapability` / `parseCapabilityV2`, which follow
 * construct-veil-protocol `Capability::encode` / `CapabilityV2::encode`.
 */
object VeilCapabilityBlob {

    /**
     * The issuer key — construct-landing's relay-config signing key. **Canon:** iOS
     * `VEILConfig.relayConfigSigningKey`; rotating it changes both files.
     */
    const val ISSUER_KEY_HEX = "8a0ee71cd95f86a9f6877211accefaff6bb97f3051b3b2141f1c71690b9a2dcf"

    class Invalid(reason: String) : Exception(reason)

    /** B2, bearer: whoever holds the blob may use it. Times are Unix seconds; 0 = no expiry. */
    data class Bearer(val notBefore: Long, val notAfter: Long, val scope: String)

    /** B1, key-bound: usable only with the Ed25519 secret matching [veilPk]. */
    class KeyBound(val veilPk: ByteArray, val role: Int, val notBefore: Long, val notAfter: Long, val scope: String)

    private const val SIG_LEN = 64

    /**
     * `ticket_id[16] ‖ auth_key[32] ‖ not_before[8 LE] ‖ not_after[8 LE] ‖ suite_id[1]
     * ‖ scope_len[1] ‖ scope ‖ sig[64]`; signed: `"veil-cap-v1" ‖ blob[0..65) ‖ scope`.
     */
    fun parseBearer(blob: ByteArray, issuerKey: ByteArray = ISSUER_KEY, nowSeconds: Long = now()): Bearer {
        val fixed = 16 + 32 + 8 + 8 + 1 + 1
        val scope = scopeOf(blob, fixed)
        verify(issuerKey, "veil-cap-v1", blob, signedPrefix = fixed - 1, scope)
        val notAfter = u64le(blob, 56)
        checkWindow(notAfter, nowSeconds)
        return Bearer(u64le(blob, 48), notAfter, String(scope, Charsets.UTF_8))
    }

    /**
     * `ticket_id[16] ‖ veil_pk[32] ‖ role[1] ‖ not_before[8 LE] ‖ not_after[8 LE] ‖ suite_id[1]
     * ‖ scope_len[1] ‖ scope ‖ sig[64]`; signed: `"veil-cap-v2" ‖ blob[0..66) ‖ scope`.
     */
    fun parseKeyBound(blob: ByteArray, issuerKey: ByteArray = ISSUER_KEY, nowSeconds: Long = now()): KeyBound {
        val fixed = 16 + 32 + 1 + 8 + 8 + 1 + 1
        val scope = scopeOf(blob, fixed)
        verify(issuerKey, "veil-cap-v2", blob, signedPrefix = fixed - 1, scope)
        val notAfter = u64le(blob, 57)
        checkWindow(notAfter, nowSeconds)
        return KeyBound(
            veilPk = blob.copyOfRange(16, 48),
            role = blob[48].toInt() and 0xFF,
            notBefore = u64le(blob, 49),
            notAfter = notAfter,
            scope = String(scope, Charsets.UTF_8),
        )
    }

    private val ISSUER_KEY: ByteArray = hex(ISSUER_KEY_HEX)

    /** The scope bytes, once the length byte (last of the fixed part) agrees with the blob's size. */
    private fun scopeOf(blob: ByteArray, fixed: Int): ByteArray {
        if (blob.size < fixed) throw Invalid("${blob.size} bytes, shorter than the fixed fields")
        val scopeLen = blob[fixed - 1].toInt() and 0xFF
        if (blob.size != fixed + scopeLen + SIG_LEN) throw Invalid("${blob.size} bytes, scope_len $scopeLen")
        return blob.copyOfRange(fixed, fixed + scopeLen)
    }

    private fun verify(issuerKey: ByteArray, domain: String, blob: ByteArray, signedPrefix: Int, scope: ByteArray) {
        val message = domain.toByteArray(Charsets.US_ASCII) + blob.copyOfRange(0, signedPrefix) + scope
        val signature = blob.copyOfRange(blob.size - SIG_LEN, blob.size)
        try {
            Ed25519Verify(issuerKey).verify(signature, message)
        } catch (e: GeneralSecurityException) {
            throw Invalid("issuer signature does not verify")
        }
    }

    private fun checkWindow(notAfter: Long, nowSeconds: Long) {
        if (notAfter != 0L && nowSeconds > notAfter) throw Invalid("expired at $notAfter")
    }

    private fun u64le(b: ByteArray, at: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = v or ((b[at + i].toLong() and 0xFF) shl (8 * i))
        return v
    }

    private fun now() = System.currentTimeMillis() / 1000

    internal fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
