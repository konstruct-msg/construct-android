package com.construct.messenger.invite

import com.construct.messenger.crypto.CryptoManager
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class MintedInvite(
    val jti: String,
    val issuedAtEpochSec: Long,
    val ttlSeconds: Int,
    val payload: String,
    val deepLink: String,
)

@Singleton
class InviteGenerator @Inject constructor(
    private val cryptoManager: CryptoManager,
) {
    fun mintLink(
        userId: String,
        deviceId: String,
        username: String? = null,
        server: String = InviteConfig.DEFAULT_SERVER,
        ttlSeconds: Int = InviteConfig.TTL_SECONDS.toInt(),
    ): MintedInvite {
        val invite = generate(userId, deviceId, username, server, ttlSeconds)
        val payload = invite.toBase64Url()
        return MintedInvite(
            jti = invite.jti,
            issuedAtEpochSec = invite.ts,
            ttlSeconds = invite.ttl ?: ttlSeconds,
            payload = payload,
            deepLink = "${InviteConfig.DEEP_LINK_SCHEME}?invite=$payload",
        )
    }

    fun generate(
        userId: String,
        deviceId: String,
        username: String?,
        server: String,
        ttlSeconds: Int,
    ): InviteObject {
        if (runCatching { UUID.fromString(userId) }.isFailure) throw InviteException.InvalidUserId
        val device = deviceId.lowercase()
        if (device.length != InviteConfig.DEVICE_ID_LENGTH ||
            !InviteConfig.DEVICE_ID_REGEX.matches(device)
        ) {
            throw InviteException.InvalidDeviceId
        }
        val version = InviteConfig.CURRENT_VERSION
        val statedTtl = if (InviteConfig.carriesTtl(version)) ttlSeconds else null
        val un = username?.trim()?.takeIf { it.isNotEmpty() }
        val unsigned = InviteObject(
            v = version,
            jti = UUID.randomUUID().toString().lowercase(),
            uuid = userId.lowercase(),
            deviceId = device,
            server = InviteConfig.normalizeServer(server),
            ephKey = "",
            ts = System.currentTimeMillis() / 1000,
            sig = "",
            un = un,
            ttl = statedTtl,
        )
        val canonical = unsigned.canonicalString()
        val signature = try {
            cryptoManager.signInvite(canonical)
        } catch (_: Exception) {
            throw InviteException.MissingIdentityKey
        }
        val verifying = cryptoManager.verifyingKeyFromSigningSecret()
        if (!cryptoManager.verifyInvite(canonical, signature, verifying)) {
            throw InviteException.SigningFailed
        }
        return unsigned.copy(sig = b64encode(signature)).also { it.validate() }
    }
}
