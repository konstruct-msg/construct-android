package com.construct.messenger.data.local

import com.construct.messenger.diagnostics.Log
import com.construct.messenger.crypto.CryptoManager
import com.construct.messenger.data.local.db.PeerDeviceDao
import com.construct.messenger.data.local.db.PeerDeviceEntity
import com.construct.messenger.data.local.db.UserDao
import com.construct.messenger.data.model.IdentityIds
import com.construct.messenger.data.model.SecurityNotice
import com.construct.messenger.security.SecurityNotices
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Durable account -> device translation above the crypto seam.
 *
 * The registry is a server answer cache, not a trust root. A device is accepted only when its
 * advertised id equals deriveDeviceId(identityPublic); the core still receives device ids only.
 */
@Singleton
class PeerDeviceRegistry @Inject constructor(
    private val peerDeviceDao: PeerDeviceDao,
    private val userDao: UserDao,
    private val cryptoManager: CryptoManager,
    private val keystoreManager: KeystoreManager,
    private val securityNotices: SecurityNotices,
) {
    suspend fun record(
        accountId: String,
        deviceId: String,
        identityPublic: ByteArray,
        platform: Int = 0,
        activeDeviceIds: List<String>? = null,
    ) {
        if (!accepts(accountId, deviceId, identityPublic)) return
        val now = System.currentTimeMillis()
        val existing = peerDeviceDao.forDevice(deviceId)
        if (existing != null && existing.accountId != accountId) {
            Log.e(TAG, "refusing to rehome device ${deviceId.take(8)}…")
            return
        }
        if (isNewDeviceEvent(
                accountId = accountId,
                ownAccountId = keystoreManager.getUserId(),
                listedBefore = accountId.lowercase() in keystoreManager.deviceSetsListed(),
                alreadyKnown = existing != null,
            )
        ) {
            Log.w(TAG, "NEW_DEVICE: ${accountId.take(8)}… has device ${deviceId.take(8)}… not in its listed set")
            securityNotices.raise(accountId, SecurityNotice.NEW_DEVICE)
        }
        peerDeviceDao.upsert(
            PeerDeviceEntity(
                accountId = accountId,
                deviceId = deviceId,
                identityPublic = identityPublic.copyOf(),
                platform = platform,
                firstSeenAtMs = existing?.firstSeenAtMs ?: now,
                lastSeenAtMs = now,
            ),
        )
        val active = activeDeviceIds.orEmpty().filter(IdentityIds::isCryptoDeviceId)
        if (active.isNotEmpty()) {
            peerDeviceDao.deleteNotActive(accountId, active)
        }
    }

    suspend fun recordAll(
        accountId: String,
        devices: List<PeerDevice>,
        activeDeviceIds: List<String> = emptyList(),
    ) {
        devices.forEach { record(accountId, it.deviceId, it.identityPublic, it.platform) }
        val active = activeDeviceIds.filter(IdentityIds::isCryptoDeviceId)
        if (active.isNotEmpty()) {
            peerDeviceDao.deleteNotActive(accountId, active)
        }
        // After the rows: the first full list is first sight for every device on it.
        if (devices.isNotEmpty()) keystoreManager.markDeviceSetListed(accountId)
    }

    suspend fun knownDevices(accountId: String): List<PeerDeviceEntity> =
        peerDeviceDao.forAccount(accountId)

    suspend fun resolveDeviceId(accountOrDeviceId: String): String? {
        if (IdentityIds.isCryptoDeviceId(accountOrDeviceId)) return accountOrDeviceId
        peerDeviceDao.forAccount(accountOrDeviceId).firstOrNull()?.let { return it.deviceId }
        val legacyIdentity = userDao.getById(accountOrDeviceId)?.identityPublic ?: return null
        if (legacyIdentity.isEmpty()) return null
        val derived = cryptoManager.deriveDeviceIdFromIdentity(legacyIdentity)
        if (!IdentityIds.isCryptoDeviceId(derived)) return null
        record(accountOrDeviceId, derived, legacyIdentity)
        return derived
    }

    suspend fun accountIdForDevice(deviceId: String): String? =
        peerDeviceDao.forDevice(deviceId)?.accountId

    suspend fun identityForDevice(deviceId: String): ByteArray? =
        peerDeviceDao.forDevice(deviceId)?.identityPublic

    private fun accepts(accountId: String, deviceId: String, identityPublic: ByteArray): Boolean {
        if (accountId.isEmpty() || !IdentityIds.isCryptoDeviceId(deviceId) || identityPublic.isEmpty()) {
            return false
        }
        val derived = cryptoManager.deriveDeviceIdFromIdentity(identityPublic)
        if (derived != deviceId) {
            Log.e(TAG, "rejecting mismatched device/key pair ${deviceId.take(8)}…")
            return false
        }
        return true
    }

    data class PeerDevice(
        val deviceId: String,
        val identityPublic: ByteArray,
        val platform: Int = 0,
    )

    companion object {
        private const val TAG = "PeerDeviceRegistry"

        /**
         * A device is a security event when its account's full list was already had and did not
         * name it. Before the first full list every device is first sight — an invite records one
         * device and the send fan-out then lists the rest. Our own account's devices are not this
         * event. `decisions/a-new-device-is-the-security-event.md`.
         */
        internal fun isNewDeviceEvent(
            accountId: String,
            ownAccountId: String?,
            listedBefore: Boolean,
            alreadyKnown: Boolean,
        ): Boolean = listedBefore && !alreadyKnown && !accountId.equals(ownAccountId, ignoreCase = true)
    }
}
