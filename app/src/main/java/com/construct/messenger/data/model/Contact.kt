package com.construct.messenger.data.model

data class Contact(
    val userId: String,
    val displayName: String,
    val username: String = "",
    /** An unacknowledged security event; [SecurityNotice.NONE] when there is none. */
    val securityNotice: SecurityNotice = SecurityNotice.NONE,
    /** The last Key Transparency verdict on their bundle. */
    val ktStatus: KtStatus = KtStatus.UNVERIFIED,
    /** The user's own name for them; [displayName] already is it when set. */
    val localName: String? = null,
    /** Their avatar as they shared it, a JPEG; `null` = the identicon. */
    val avatar: ByteArray? = null,
) {
    /** What to warn about, if anything — the event first, then a failed proof. */
    val trustAlert: ContactTrustAlert? get() = ContactTrustAlert.of(securityNotice, ktStatus)
}
