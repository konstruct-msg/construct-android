package com.construct.messenger.data.local

import com.construct.messenger.data.local.db.ServerMessageIdDao
import com.construct.messenger.data.local.db.ServerMessageIdEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.construct_core.LocalStore

/**
 * Where the server's id of each sealed copy we sent is kept beside the message it carries
 * (TODO 136) — what [com.construct.messenger.service.ServerMessageIds] reads and writes. Both ids
 * lowercase. [ServerMessageIdStoreContract] holds Room's and the core's to one behaviour.
 */
interface ServerMessageIdStore {
    /** Writes the pair over any row with [serverId]. */
    suspend fun record(serverId: String, localId: String, recordedAtMs: Long)

    suspend fun localId(serverId: String): String?

    /** Forgets pairs recorded before [cutoffMs]. */
    suspend fun forgetBefore(cutoffMs: Long)
}

/** Built by `DatabaseModule`, which provides no `ServerMessageIdDao`. */
class RoomServerMessageIdStore(private val dao: ServerMessageIdDao) : ServerMessageIdStore {
    override suspend fun record(serverId: String, localId: String, recordedAtMs: Long) =
        dao.upsert(ServerMessageIdEntity(serverId, localId, recordedAtMs))

    override suspend fun localId(serverId: String): String? = dao.localId(serverId)

    override suspend fun forgetBefore(cutoffMs: Long) {
        dao.pruneOlderThan(cutoffMs)
    }
}

/** On the core's `LocalStore`, which keeps the same table. Not wired into the app yet. */
class CoreServerMessageIdStore(private val store: LocalStore) : ServerMessageIdStore {
    override suspend fun record(serverId: String, localId: String, recordedAtMs: Long) =
        withContext(Dispatchers.IO) { store.recordServerMessageId(serverId, localId, recordedAtMs) }

    override suspend fun localId(serverId: String): String? = withContext(Dispatchers.IO) { store.localMessageId(serverId) }

    override suspend fun forgetBefore(cutoffMs: Long) {
        withContext(Dispatchers.IO) { store.forgetServerMessageIdsBefore(cutoffMs) }
    }
}
