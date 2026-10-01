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

    /** Hosts whose `/add` is ours (iOS `applinks:` entitlement, Android App Links). */
    val LINK_HOSTS = setOf("konstruct.cc", "web.konstruct.cc")

    /**
     * The link a person copies or shares. **Canon:** iOS `ContactQRCodeView.copyLink` →
     * `generateDeepLink(useHTTPS: true)`: `https://<host>/add?invite=…`, which opens the app where
     * it is installed (Universal Links / App Links) and otherwise lands on the page at that address,
     * which hands over to `konstruct://add`. A bare `konstruct://` link is not a link in most
     * messengers — it is not tappable, and the person it is sent to cannot open it at all without
     * the app. The QR keeps carrying the payload alone.
     */
    fun shareLink(payload: String, server: String = DEFAULT_SERVER): String =
        "https://${normalizeServer(server)}/add?invite=$payload"

    /** A `konstruct://add` link, or an `https` one to `/add` on one of [LINK_HOSTS]. */
    fun isInviteLink(raw: String): Boolean {
        val value = raw.trim()
        if (value.startsWith(DEEP_LINK_SCHEME)) return true
        val uri = runCatching { java.net.URI(value) }.getOrNull() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) &&
            uri.host?.lowercase() in LINK_HOSTS &&
            (uri.path == "/add" || uri.path.startsWith("/add/"))
    }

    /** The server takes `min(INVITE_TTL_SECONDS, ttl)`; the client clamps identically. */
    fun effectiveTtl(stated: Int): Long = minOf(TTL_SECONDS, stated.toLong())

    fun normalizeServer(server: String): String {
        var value = server.trim()
        value = value.removePrefix("https://").removePrefix("http://")
        return value.trimEnd('/')
    }
}
