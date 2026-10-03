package com.construct.messenger.data.model

/**
 * The verdict of the last Key Transparency check of a contact's bundle. Codes are iOS `KTStatus`
 * (`User+CoreDataProperties.swift`), so a history snapshot or a log reads the same on both.
 * iOS's 2, `keyChanged`, is not written on either platform and is not used here.
 */
enum class KtStatus(val code: Int) {
    /** No proof judged yet — a new contact, or a server that sends none. */
    UNVERIFIED(0),

    /** The key server's proof for their identity key verified against a signed tree head. */
    VERIFIED(1),

    /** The proof did not verify: malformed, not in the tree, or the head's signature is bad. */
    FAILED(3),
    ;

    companion object {
        fun of(code: Int): KtStatus = entries.firstOrNull { it.code == code } ?: UNVERIFIED
    }
}

/**
 * The warning shown for a contact, whichever source raised it. **Canon:** iOS `ContactTrustAlert`.
 */
enum class ContactTrustAlert {
    /** They named an account address other than the one pinned for them. */
    ADDRESS_CHANGED,

    /** The key server's proof for their bundle did not verify. */
    VERIFICATION_FAILED,
    ;

    companion object {
        /** A pending event outranks a failed proof: it is the one the user has to acknowledge. */
        fun of(notice: SecurityNotice, kt: KtStatus): ContactTrustAlert? = when {
            notice == SecurityNotice.ADDRESS_CHANGED -> ADDRESS_CHANGED
            kt == KtStatus.FAILED -> VERIFICATION_FAILED
            else -> null
        }
    }
}
