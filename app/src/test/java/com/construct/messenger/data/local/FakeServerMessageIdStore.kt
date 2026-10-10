package com.construct.messenger.data.local

/** [ServerMessageIdStore] in a map, for tests; [ServerMessageIdStoreContract] holds it to Room's behaviour. */
internal class FakeServerMessageIdStore : ServerMessageIdStore {
    /** server id → (local id, recorded at). */
    val rows = linkedMapOf<String, Pair<String, Long>>()

    override suspend fun record(serverId: String, localId: String, recordedAtMs: Long) {
        rows[serverId] = localId to recordedAtMs
    }

    override suspend fun localId(serverId: String): String? = rows[serverId]?.first

    override suspend fun forgetBefore(cutoffMs: Long) {
        rows.values.removeAll { it.second < cutoffMs }
    }
}
