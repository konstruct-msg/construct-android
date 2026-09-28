package com.construct.messenger.data.model

data class Contact(
    val userId: String,
    val displayName: String,
    val username: String = "",
    /** An unacknowledged security event; [SecurityNotice.NONE] when there is none. */
    val securityNotice: SecurityNotice = SecurityNotice.NONE,
)
