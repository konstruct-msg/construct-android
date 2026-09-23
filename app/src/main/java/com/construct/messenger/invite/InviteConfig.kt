package com.construct.messenger.invite

/**
 * Invite protocol constants.
 *
 * **Canon:** iOS `InviteConfig` in `Constants.swift` +
 * `INVITE_LIST_REVOKE_SERVER_SPEC`. Android is greenfield and mints **v5**
 * (iOS still mints v4 until `inviteV5Minting`; it already decodes v5).
 */
object InviteConfig {
    val supportedVersions: Set<Int> = setOf(1, 2, 3, 4, 5)

    /** Version written when generating. Spec item 4: QR ttl=300, link=43200. */
    const val CURRENT_VERSION = 5

    const val TTL_SECONDS = 43_200L
    const val QR_TTL_SECONDS = 300
    const val MIN_TTL_SECONDS = 60
    const val MAX_FUTURE_SKEW_SECONDS = 300L
    const val DEVICE_ID_LENGTH = 32
    val DEVICE_ID_REGEX = Regex("^[a-f0-9]{32}$")
    const val EPH_KEY_LENGTH = 32
    const val SIGNATURE_LENGTH = 64
    const val DEFAULT_SERVER = "konstruct.cc"
    const val DEEP_LINK_SCHEME = "konstruct://add"

    fun carriesEphKey(version: Int): Boolean = version <= 3
    fun carriesTtl(version: Int): Boolean = version >= 5

    fun effectiveTtl(stated: Int?): Long {
        if (stated == null) return TTL_SECONDS
        return minOf(TTL_SECONDS, stated.toLong())
    }

    fun normalizeServer(server: String): String {
        var value = server.trim()
        value = value.removePrefix("https://").removePrefix("http://")
        return value.trimEnd('/')
    }
}
