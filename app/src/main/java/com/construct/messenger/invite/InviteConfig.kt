package com.construct.messenger.invite

/**
 * Invite protocol constants.
 *
 * **Canon:** iOS `InviteConfig` in `Constants.swift` + `INVITE_LIST_REVOKE_SERVER_SPEC`.
 * v5 is the only version minted and accepted, by both clients and the server, since 2026-09-28
 * (`decisions/invite-carries-the-account-address.md`).
 */
object InviteConfig {
    const val VERSION = 5

    const val TTL_SECONDS = 43_200L
    const val QR_TTL_SECONDS = 300
    const val MIN_TTL_SECONDS = 60
    const val MAX_FUTURE_SKEW_SECONDS = 300L
    const val DEVICE_ID_LENGTH = 32
    val DEVICE_ID_REGEX = Regex("^[a-f0-9]{32}$")
    const val SIGNATURE_LENGTH = 64
    const val DEFAULT_SERVER = "konstruct.cc"
    const val DEEP_LINK_SCHEME = "konstruct://add"

    /** The server takes `min(INVITE_TTL_SECONDS, ttl)`; the client clamps identically. */
    fun effectiveTtl(stated: Int): Long = minOf(TTL_SECONDS, stated.toLong())

    fun normalizeServer(server: String): String {
        var value = server.trim()
        value = value.removePrefix("https://").removePrefix("http://")
        return value.trimEnd('/')
    }
}
