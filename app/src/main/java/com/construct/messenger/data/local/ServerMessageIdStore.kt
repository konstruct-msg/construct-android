package com.construct.messenger.data.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.construct_core.LocalStore

/**
 * Where the server's id of each sealed copy we sent is kept beside the message it carries
 * (TODO 136) — what [com.construct.messenger.service.ServerMessageIds] reads and writes. Both ids
 * lowercase. [ServerMessageIdStoreContract] holds the stores to one behaviour.
 */
interface ServerMessageIdStore {
    /** Writes the pair over any row with [serverId]. */
    suspend fun record(serverId: String, localId: String, recordedAtMs: Long)

    suspend fun localId(serverId: String): String?

    /** Forgets pairs recorded before [cutoffMs]. */
    suspend fun forgetBefore(cutoffMs: Long)
}

/** On the core's `LocalStore`, which keeps the same table. */
class CoreServerMessageIdStore(private val store: LocalStore) : ServerMessageIdStore {
    override suspend fun record(serverId: String, localId: String, recordedAtMs: Long) =
        withContext(Dispatchers.IO) { store.recordServerMessageId(serverId, localId, recordedAtMs) }

    override suspend fun localId(serverId: String): String? = withContext(Dispatchers.IO) { store.localMessageId(serverId) }

    override suspend fun forgetBefore(cutoffMs: Long) {
        withContext(Dispatchers.IO) { store.forgetServerMessageIdsBefore(cutoffMs) }
    }
}
