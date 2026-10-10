package com.construct.messenger.data.local

/** [PeerDeviceStore] in a map, for tests; [PeerDeviceStoreContract] holds it to Room's behaviour. */
internal class FakePeerDeviceStore : PeerDeviceStore {
    val rows = linkedMapOf<String, PeerDeviceRecord>()

    override suspend fun forAccount(accountId: String): List<PeerDeviceRecord> =
        rows.values.filter { it.accountId == accountId }.sortedWith(compareBy({ it.firstSeenAtMs }, { it.deviceId }))

    override suspend fun forDevice(deviceId: String): PeerDeviceRecord? = rows[deviceId]

    override suspend fun record(device: PeerDeviceRecord): Boolean {
        if (device.deviceId in rows) return false
        rows[device.deviceId] = device
        return true
    }

    override suspend fun retain(accountId: String, active: List<String>) {
        if (active.isEmpty()) return
        rows.values.removeAll { it.accountId == accountId && it.deviceId !in active }
    }
}
