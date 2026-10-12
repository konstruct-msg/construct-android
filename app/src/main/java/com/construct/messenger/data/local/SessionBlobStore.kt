package com.construct.messenger.data.local

import com.construct.messenger.data.local.db.SessionStateDao
import com.construct.messenger.data.local.db.SessionStateEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.construct_core.LocalStore

/**
 * Opaque CFE blobs by key — what [SessionStateStore] writes (TODO 136). Room's `session_state`
 * today; the core's `kv` next, under the same keys (`session:<device>`, the orchestrator's).
 * [SessionBlobStoreContract] holds both to one behaviour.
 */
interface SessionBlobStore {
    /** Writes [bytes] over the blob under [key]. */
    suspend fun put(key: String, bytes: ByteArray)

    suspend fun get(key: String): ByteArray?

    suspend fun remove(key: String)

    /** Every blob whose key starts with [prefix] — compared as text, no pattern characters. */
    suspend fun withPrefix(prefix: String): Map<String, ByteArray>
}

/** Built by `LocalStores`; nothing provides a `SessionStateDao`. */
class RoomSessionBlobStore(
    private val dao: SessionStateDao,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : SessionBlobStore {
    override suspend fun put(key: String, bytes: ByteArray) = dao.upsert(SessionStateEntity(key, bytes, nowMs()))

    override suspend fun get(key: String): ByteArray? = dao.get(key)?.cfeBytes

    override suspend fun remove(key: String) = dao.delete(key)

    override suspend fun withPrefix(prefix: String): Map<String, ByteArray> =
        dao.getAll().filter { it.storeKey.startsWith(prefix) }.associate { it.storeKey to it.cfeBytes }
}

/** On the core's `LocalStore` `kv`. */
class CoreSessionBlobStore(private val store: LocalStore) : SessionBlobStore {
    override suspend fun put(key: String, bytes: ByteArray) {
        withContext(Dispatchers.IO) { store.put(key, bytes) }
    }

    override suspend fun get(key: String): ByteArray? = withContext(Dispatchers.IO) { store.get(key) }

    override suspend fun remove(key: String) {
        withContext(Dispatchers.IO) { store.remove(key) }
    }

    override suspend fun withPrefix(prefix: String): Map<String, ByteArray> =
        withContext(Dispatchers.IO) { store.entriesWithPrefix(prefix).associate { it.key to it.value } }
}
