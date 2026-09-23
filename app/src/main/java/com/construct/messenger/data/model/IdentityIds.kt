package com.construct.messenger.data.model

/** The server-assigned account identity used by gRPC, Room and the UI. */
@JvmInline
value class ServerUserId(val rawValue: String)

/** The self-certifying device identity used by construct-core session state. */
@JvmInline
value class CryptoDeviceId(val rawValue: String)

/** Explicit seam between account-space and device-space identifiers. */
object IdentityIds {
    private val cryptoDevicePattern = Regex("^[0-9a-f]{32}$")

    fun isCryptoDeviceId(value: String): Boolean = cryptoDevicePattern.matches(value)

    fun requireCryptoDeviceId(value: String): CryptoDeviceId {
        require(isCryptoDeviceId(value)) { "not a CryptoDeviceId: $value" }
        return CryptoDeviceId(value)
    }
}
