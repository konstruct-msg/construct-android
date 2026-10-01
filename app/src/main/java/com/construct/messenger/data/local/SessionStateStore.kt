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
 *
 * Every session save writes the orchestrator state beside it, as iOS `handleStorageAction` does.
 * The core asks for that state only where nothing else is saved, and counts on the platform for
 * the rest. Since core 0.26.0 it holds the envelope book — a session's envelope keys — and a
 * session restored without its pair in the book is refused (`SESSION_PREDATES_ENVELOPE`). Until
 * 2026-10-01 nothing here exported it: every restart would have reopened every conversation.
 */
@Singleton
class SessionStateStore @Inject constructor(
    private val sessionStateDao: SessionStateDao,
    private val orchestratorState: OrchestratorStateSource,
) {

    /** Persist one CFE storage action without reconstructing a string key. */
    suspend fun saveSecureStore(slot: CfeSecureStoreSlot, cfeBytes: ByteArray) {
        val key = keyFor(slot)
        if (cfeBytes.isEmpty()) {
            sessionStateDao.delete(key)
        } else {
            saveSession(key, cfeBytes)
        }
        if (slot is CfeSecureStoreSlot.Session) {
            orchestratorState.snapshot()?.let { saveSession(ORCHESTRATOR_STATE_KEY, it) }
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

/** The core's coordination state as it stands now — `null` before the core is up. Served by
 * `CryptoManager`. */
fun interface OrchestratorStateSource {
    fun snapshot(): ByteArray?
}
