package com.construct.messenger.invite

import com.construct.messenger.crypto.CryptoManager
import com.construct.messenger.data.local.KeystoreManager
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
    private val keystoreManager: KeystoreManager,
) {
    /**
     * An invite for the `https://…/add` link a person shares ([InviteConfig.shareLink]). It never
     * carries [username] — [linkUsername] says why.
     */
    fun mintLink(
        userId: String,
        deviceId: String,
        username: String? = null,
        server: String = InviteConfig.DEFAULT_SERVER,
        ttlSeconds: Int = InviteConfig.TTL_SECONDS.toInt(),
    ): MintedInvite {
        val invite = generate(userId, deviceId, linkUsername(username, useHttps = true), server, ttlSeconds)
        val payload = invite.toBase64Url()
        return MintedInvite(invite.jti, invite.ts, invite.ttl, payload, InviteConfig.shareLink(payload, server))
    }

    /**
     * An invite for a QR on screen: it carries [username], so the person who scans it sees who they
     * added (iOS does the same since `0522e471`). Its [MintedInvite.deepLink] is the `konstruct://`
     * form, which may carry it too.
     */
    fun mintQr(
        userId: String,
        deviceId: String,
        username: String?,
        server: String = InviteConfig.DEFAULT_SERVER,
        ttlSeconds: Int = InviteConfig.QR_TTL_SECONDS,
    ): MintedInvite {
        val invite = generate(userId, deviceId, linkUsername(username, useHttps = false), server, ttlSeconds)
        val payload = invite.toBase64Url()
        return MintedInvite(invite.jti, invite.ts, invite.ttl, payload, "${InviteConfig.DEEP_LINK_SCHEME}?invite=$payload")
    }

    /**
     * Refuses without this account's address ([InviteException.NoAccountAddress]): an invite
     * naming none would leave the redeemer writing to the server-assigned id. The UI gates on the
     * recovery phrase before it gets here.
     */
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
        val addr = keystoreManager.getOwnAccountAddress() ?: throw InviteException.NoAccountAddress
        val un = username?.trim()?.takeIf { it.isNotEmpty() }
        val unsigned = InviteObject(
            v = InviteConfig.VERSION,
            jti = UUID.randomUUID().toString().lowercase(),
            uuid = userId.lowercase(),
            deviceId = device,
            server = InviteConfig.normalizeServer(server),
            ts = System.currentTimeMillis() / 1000,
            sig = "",
            un = un,
            ttl = ttlSeconds,
            addr = addr,
        )
        val canonical = unsigned.canonicalString()
        val signature = try {
            cryptoManager.signInvite(canonical)
        } catch (_: Exception) {
            throw InviteException.MissingIdentityKey
        }
        val verifying = cryptoManager.verifyingKey()
        if (!cryptoManager.verifyInvite(canonical, signature, verifying)) {
            throw InviteException.SigningFailed
        }
        return unsigned.copy(sig = b64encode(signature)).also { it.validate() }
    }

    companion object {
        /**
         * The username an invite may carry. **Canon:** iOS `InviteGenerator.linkUsername`. An HTTPS
         * link travels through other messengers: their link previews and scanners fetch it and keep
         * it in their logs. Without the username it names an opaque account id; with it, anyone
         * holding those logs could tie the invite to the name the person goes by. The QR and a
         * `konstruct://` link carry it, so the new contact sees who it added. Decided with the owner
         * 2026-10-02 (construct-docs TODO 95, 98). The rule lives here, not at each caller.
         */
        fun linkUsername(username: String?, useHttps: Boolean): String? = if (useHttps) null else username
    }
}
