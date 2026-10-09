package com.construct.messenger.data.local

import com.construct.messenger.diagnostics.Log
import com.construct.messenger.crypto.CryptoManager
import com.construct.messenger.data.local.db.PeerDeviceDao
import com.construct.messenger.data.local.db.PeerDeviceEntity
import com.construct.messenger.data.model.IdentityIds
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Durable account -> device translation above the crypto seam.
 *
 * The registry is a server answer cache, not a trust root. A device is accepted only when its
 * advertised id equals deriveDeviceId(identityPublic); the core still receives device ids only.
 *
 * A device the account did not have is recorded and nothing more: no alert, since it cannot tell a
 * device the contact linked from one the server added
 * (`decisions/new-device-alarm-waits-for-cross-signing.md`).
 */
@Singleton
class PeerDeviceRegistry @Inject constructor(
    private val peerDeviceDao: PeerDeviceDao,
    private val contacts: ContactStore,
    private val cryptoManager: CryptoManager,
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
    }

    suspend fun knownDevices(accountId: String): List<PeerDeviceEntity> =
        peerDeviceDao.forAccount(accountId)

    suspend fun resolveDeviceId(accountOrDeviceId: String): String? {
        if (IdentityIds.isCryptoDeviceId(accountOrDeviceId)) return accountOrDeviceId
        peerDeviceDao.forAccount(accountOrDeviceId).firstOrNull()?.let { return it.deviceId }
        val legacyIdentity = contacts.get(accountOrDeviceId)?.identityPublic ?: return null
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

    private companion object {
        const val TAG = "PeerDeviceRegistry"
    }
}
