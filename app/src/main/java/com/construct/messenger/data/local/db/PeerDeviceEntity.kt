package com.construct.messenger.data.local.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Upsert

/** Durable account -> device registry; identityPublic is the pinned device key. */
@Entity(
    tableName = "peer_devices",
    primaryKeys = ["accountId", "deviceId"],
)
data class PeerDeviceEntity(
    val accountId: String,
    val deviceId: String,
    val identityPublic: ByteArray,
    val platform: Int = 0,
    val firstSeenAtMs: Long,
    val lastSeenAtMs: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PeerDeviceEntity) return false
        return accountId == other.accountId &&
            deviceId == other.deviceId &&
            identityPublic.contentEquals(other.identityPublic) &&
            platform == other.platform &&
            firstSeenAtMs == other.firstSeenAtMs &&
            lastSeenAtMs == other.lastSeenAtMs
    }

    override fun hashCode(): Int {
        var result = accountId.hashCode()
        result = 31 * result + deviceId.hashCode()
        result = 31 * result + identityPublic.contentHashCode()
        result = 31 * result + platform
        result = 31 * result + firstSeenAtMs.hashCode()
        result = 31 * result + lastSeenAtMs.hashCode()
        return result
    }
}

@Dao
interface PeerDeviceDao {
    @Query("SELECT * FROM peer_devices WHERE accountId = :accountId ORDER BY firstSeenAtMs ASC, deviceId ASC")
    suspend fun forAccount(accountId: String): List<PeerDeviceEntity>

    @Query("SELECT * FROM peer_devices WHERE deviceId = :deviceId LIMIT 1")
    suspend fun forDevice(deviceId: String): PeerDeviceEntity?

    @Upsert
    suspend fun upsert(device: PeerDeviceEntity)

    @Upsert
    suspend fun upsertAll(devices: List<PeerDeviceEntity>)

    @Query("DELETE FROM peer_devices WHERE accountId = :accountId AND deviceId NOT IN (:activeDeviceIds)")
    suspend fun deleteNotActive(accountId: String, activeDeviceIds: List<String>)
}
