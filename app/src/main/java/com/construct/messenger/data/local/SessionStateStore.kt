package com.construct.messenger.data.local

import com.construct.messenger.data.local.db.SessionStateDao
import com.construct.messenger.data.local.db.SessionStateEntity
import javax.inject.Inject
import javax.inject.Singleton
import uniffi.construct_core.CfeAction
import uniffi.construct_core.CfeSecureStoreSlot

/**
 * Persistence for CFE session bytes. The Rust core names the durable object with
 * `CfeSecureStoreSlot`; Android only maps that type to a Room key. Blobs are opaque CFE binary
 * (`docs/FFI_BINARY_FORMAT.md`) and are never parsed here.
 */
@Singleton
class SessionStateStore @Inject constructor(
    private val sessionStateDao: SessionStateDao,
) {

    /** Persist one CFE storage action without reconstructing a string key. */
    suspend fun saveSecureStore(slot: CfeSecureStoreSlot, cfeBytes: ByteArray) {
        val key = keyFor(slot)
        if (cfeBytes.isEmpty()) {
            sessionStateDao.delete(key)
        } else {
            saveSession(key, cfeBytes)
        }
    }

    /** Apply every typed secure-store action returned by CFE. */
    suspend fun saveCfeActions(actions: List<CfeAction>): Boolean {
        var savedSession = false
        for (action in actions) {
            if (action is CfeAction.SaveToSecureStore) {
                saveSecureStore(action.slot, action.data)
                if (action.slot is CfeSecureStoreSlot.Session) savedSession = true
            }
        }
        return savedSession
    }

    // ── Session blobs ────────────────────────────────────────────────────────

    suspend fun saveSession(storeKey: String, cfeBytes: ByteArray) {
        sessionStateDao.upsert(
            SessionStateEntity(
                storeKey = storeKey,
                cfeBytes = cfeBytes,
                updatedAtMs = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun loadSession(storeKey: String): ByteArray? =
        sessionStateDao.get(storeKey)?.cfeBytes

    /** All persisted blobs, keyed by their opaque store key — for startup restore. */
    suspend fun loadAllSessions(): Map<String, ByteArray> =
        sessionStateDao.getAll().associate { it.storeKey to it.cfeBytes }

    suspend fun removeSession(storeKey: String) = sessionStateDao.delete(storeKey)

    private fun keyFor(slot: CfeSecureStoreSlot): String = when (slot) {
        is CfeSecureStoreSlot.Session -> "session:${slot.contactId}"
        CfeSecureStoreSlot.OrchestratorState -> ORCHESTRATOR_STATE_KEY
    }

    companion object {
        const val SESSION_KEY_PREFIX = "session:"
        const val ARCHIVE_KEY_PREFIX = "archive:"
        const val ORCHESTRATOR_STATE_KEY = "core:orchestrator-state"

        /** Keys of the slots the ML-KEM-768 layer had before PQXDH v2 — deleted on restore. */
        fun isLegacyPqKey(key: String): Boolean =
            key == "core:kyber-session-state" || key.startsWith("pq-deferred:") || key.startsWith("kyber-spk:")
    }
}
