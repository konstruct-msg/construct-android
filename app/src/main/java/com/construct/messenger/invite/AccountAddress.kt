package com.construct.messenger.invite

/**
 * An account's address: its Ed25519 recovery public key. The server resolves it to the account
 * (`route_id = SHA-256(0x0001 || key)`), so a message names its recipient by a key the recipient
 * chose instead of an id the server assigned.
 *
 * Where each copy comes from, and why none comes from the server:
 *  - our own: derived from the recovery phrase on this device — at setup, or when the phrase is
 *    entered to confirm it. A key the server handed us could name another account, and every
 *    contact we invite would then write to it and lose the message without a word (an unknown
 *    address is accepted and dropped by design).
 *  - a peer's: from their signed invite ([InviteObject.addr]), verified against their device.
 *
 * **Canon:** iOS `AccountAddress`; decision `invite-carries-the-account-address.md`.
 */
object AccountAddress {
    /** An Ed25519 public key. */
    const val LENGTH = 32

    /** How an address is written where a recipient is named (`SealedInner.recipient_user_id`).
     * The server's `UserId::parse` reads exactly this form. */
    fun wire(key: ByteArray): String = "ed25519:" + hexEncode(key)

    /** The address when known, the account id otherwise. Pure, so the choice is testable apart
     * from the store. */
    fun recipientField(accountId: String, address: ByteArray?): String =
        if (address != null && address.size == LENGTH) wire(address) else accountId

    /**
     * Whether a key derived here is the one the server holds, judged by the fingerprint
     * `GetRecoveryStatus` reports: the key's leading hex in spaced groups. Formatting is ignored
     * and at least 16 bytes must match, so a truncated fingerprint cannot stand for the key.
     * The server can make this answer "no", never "yes" for a key the phrase did not produce.
     */
    fun matchesServerFingerprint(key: ByteArray, fingerprint: String): Boolean {
        val digits = fingerprint.filterNot { it.isWhitespace() }.lowercase()
        if (digits.length < 32) return false
        return hexEncode(key).startsWith(digits)
    }

    /** What the server's fingerprint says about the address this device holds. */
    enum class OwnVerdict {
        /** Nothing stored. */
        ABSENT,
        /** Stored, and the server's key for the account is its prefix. */
        CONFIRMED,
        /** Stored, and the server names another key: it came from a different account's phrase. */
        FOREIGN,
        /** Stored, and the server reports no key to compare with. */
        UNCONFIRMED,
    }

    /** Pure, so the rule is testable apart from the store and the network. **Canon:** iOS
     * `AccountAddress.verdict`. */
    fun verdict(stored: ByteArray?, serverFingerprint: String?): OwnVerdict = when {
        stored == null || stored.size != LENGTH -> OwnVerdict.ABSENT
        serverFingerprint == null -> OwnVerdict.UNCONFIRMED
        matchesServerFingerprint(stored, serverFingerprint) -> OwnVerdict.CONFIRMED
        else -> OwnVerdict.FOREIGN
    }
}
