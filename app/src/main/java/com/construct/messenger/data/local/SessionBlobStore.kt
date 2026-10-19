package com.construct.messenger.data.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.construct_core.LocalStore

/**
 * Opaque CFE blobs by key — what [SessionStateStore] writes (TODO 136). In the core's
 * `kv`, under the orchestrator's keys (`session:<device>`). [SessionBlobStoreContract] holds the
 * stores to one behaviour.
 */
interface SessionBlobStore {
    /** Writes [bytes] over the blob under [key]. */
    suspend fun put(key: String, bytes: ByteArray)

    suspend fun get(key: String): ByteArray?

    suspend fun remove(key: String)

    /** Every blob whose key starts with [prefix] — compared as text, no pattern characters. */
    suspend fun withPrefix(prefix: String): Map<String, ByteArray>
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
