package com.construct.messenger.data.local

import com.construct.messenger.data.local.db.PeerDeviceDao
import com.construct.messenger.data.local.db.PeerDeviceEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.construct_core.LocalInsert
import uniffi.construct_core.LocalPeerDevice
import uniffi.construct_core.LocalStore

/** One device of a peer's account, as the core and iOS keep it: [deviceId] is the hash of [identityPublic]. */
class PeerDeviceRecord(
    val deviceId: String,
    val accountId: String,
    val identityPublic: ByteArray,
    val firstSeenAtMs: Long,
) {
    override fun equals(other: Any?): Boolean =
        other is PeerDeviceRecord && deviceId == other.deviceId && accountId == other.accountId &&
            identityPublic.contentEquals(other.identityPublic) && firstSeenAtMs == other.firstSeenAtMs

    override fun hashCode(): Int = deviceId.hashCode()

    override fun toString(): String = "PeerDeviceRecord(${deviceId.take(8)}…, account ${accountId.take(8)}…, $firstSeenAtMs)"
}

/**
 * Where [PeerDeviceRegistry] keeps the devices it accepted (TODO 136). [PeerDeviceStoreContract]
 * holds Room's and the core's to one behaviour. Room's table also has a platform and a last-seen
 * time; nothing read them, and the core keeps neither.
 */
interface PeerDeviceStore {
    /** An account's devices, oldest first; devices first seen in the same millisecond by id. */
    suspend fun forAccount(accountId: String): List<PeerDeviceRecord>

    suspend fun forDevice(deviceId: String): PeerDeviceRecord?

    /** Records [device] unless its id is known — under any account; true when it was new. */
    suspend fun record(device: PeerDeviceRecord): Boolean

    /** Forgets [accountId]'s devices not in [active]; an empty list forgets nothing. */
    suspend fun retain(accountId: String, active: List<String>)
}

/** Built by `DatabaseModule`, which provides no `PeerDeviceDao`. */
class RoomPeerDeviceStore(private val dao: PeerDeviceDao) : PeerDeviceStore {
    override suspend fun forAccount(accountId: String): List<PeerDeviceRecord> = dao.forAccount(accountId).map { it.record() }

    override suspend fun forDevice(deviceId: String): PeerDeviceRecord? = dao.forDevice(deviceId)?.record()

    // Room's key is (account, device); the core's is the device alone, so a known id is looked up first.
    override suspend fun record(device: PeerDeviceRecord): Boolean {
        if (dao.forDevice(device.deviceId) != null) return false
        dao.upsert(
            PeerDeviceEntity(
                accountId = device.accountId,
                deviceId = device.deviceId,
                identityPublic = device.identityPublic.copyOf(),
                firstSeenAtMs = device.firstSeenAtMs,
                lastSeenAtMs = device.firstSeenAtMs,
            ),
        )
        return true
    }

    override suspend fun retain(accountId: String, active: List<String>) {
        if (active.isNotEmpty()) dao.deleteNotActive(accountId, active)
    }
}

private fun PeerDeviceEntity.record() = PeerDeviceRecord(deviceId, accountId, identityPublic, firstSeenAtMs)

/** On the core's `LocalStore`. Not wired into the app yet. */
class CorePeerDeviceStore(private val store: LocalStore) : PeerDeviceStore {
    private suspend fun <T> io(block: (LocalStore) -> T): T = withContext(Dispatchers.IO) { block(store) }

    override suspend fun forAccount(accountId: String): List<PeerDeviceRecord> = io { s -> s.peerDevices(accountId).map { it.record() } }

    override suspend fun forDevice(deviceId: String): PeerDeviceRecord? = io { it.peerDevice(deviceId)?.record() }

    override suspend fun record(device: PeerDeviceRecord): Boolean = io { s ->
        s.recordPeerDevice(LocalPeerDevice(device.deviceId, device.accountId, device.identityPublic, device.firstSeenAtMs)) ==
            LocalInsert.INSERTED
    }

    override suspend fun retain(accountId: String, active: List<String>) {
        io { it.retainPeerDevices(accountId, active) }
    }
}

private fun LocalPeerDevice.record() = PeerDeviceRecord(deviceId, accountId, identityKey, firstSeenAt)
