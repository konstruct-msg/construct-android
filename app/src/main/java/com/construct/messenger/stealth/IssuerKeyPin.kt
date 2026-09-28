package com.construct.messenger.stealth

import com.construct.messenger.invite.hexDecode

/**
 * Whether an `IssueTokens` batch may be finalized — the verifiable half of Privacy Pass.
 *
 * The batched DLEQ proof is checked against a **pinned** issuer key, never the `serverPubkey` the
 * response echoes: an issuer that answered each user with its own key could tell whose token is
 * being spent, and a proof against the echoed key would prove nothing about that. Unpinned
 * version ⇒ skip, so a freshly rotated key does not stop issuance; the per-point check still runs.
 *
 * **Canon:** iOS `BlindTokenService.issuerKeyPins` / `issueTokens` step 2b. The pin is a
 * deployment constant carried by both clients; rotate by adding the next version beside this
 * one on both, then retire the old.
 */
internal object IssuerKeyPin {

    /** v1 — from identity-service's boot log `issuer_public=zFQy29lY…`, same bytes as iOS. */
    private val pins: Map<Int, ByteArray> = mapOf(
        1 to hexDecode("cc5432dbd95825657c02424fb726b7fcffb1952fedf5b0c9a875ae186e6a2933"),
    )

    fun pinned(version: Int): ByteArray? = pins[version]?.copyOf()

    sealed interface Verdict {
        /** Finalize the first [issued] points. */
        data class Accept(val issued: Int, val dleqChecked: Boolean) : Verdict
        data class Reject(val reason: String) : Verdict
    }

    /**
     * [dleq] is `ppVerifyDleq(blinded, evaluated, proof, issuerPublic)`; passed in so the rule is
     * reachable from a JVM test.
     */
    fun check(
        requested: Int,
        blinded: List<ByteArray>,
        evaluated: List<ByteArray>,
        serverPubkey: ByteArray,
        dleqProof: ByteArray,
        keyVersion: Int,
        dleq: (List<ByteArray>, List<ByteArray>, ByteArray, ByteArray) -> Boolean,
    ): Verdict {
        val issued = evaluated.size
        // Fewer than asked is the rest of the hourly cap — the issuer grants min(asked, room).
        // More than asked cannot be finalized.
        if (issued == 0 || issued > requested) return Verdict.Reject("issued $issued of $requested")
        val pin = pinned(keyVersion) ?: return Verdict.Accept(issued, dleqChecked = false)
        if (serverPubkey.isNotEmpty() && !serverPubkey.contentEquals(pin)) {
            return Verdict.Reject("serverPubkey is not pinned issuer key v$keyVersion")
        }
        if (dleqProof.isEmpty()) return Verdict.Reject("pinned issuer key v$keyVersion, no DLEQ proof")
        if (!dleq(blinded.take(issued), evaluated, dleqProof, pin)) {
            return Verdict.Reject("DLEQ proof failed against pinned issuer key v$keyVersion")
        }
        return Verdict.Accept(issued, dleqChecked = true)
    }
}
