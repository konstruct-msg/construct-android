package com.construct.messenger.data.local

import com.construct.messenger.data.local.db.SessionMetaDao
import com.construct.messenger.data.local.db.SessionMetaEntity
import com.construct.messenger.data.local.db.SessionStateDao
import com.construct.messenger.data.local.db.SessionStateEntity
import javax.inject.Inject
import javax.inject.Singleton
import uniffi.construct_core.CfeAction
import uniffi.construct_core.CfeSecureStoreSlot

/**
 * Persistence for CFE session bytes and per-peer session metadata.
 *
 * Two distinct concerns:
 *
 * 1. **Typed core slots** — the Rust core names the durable object with
 *    `CfeSecureStoreSlot`; Android only maps that type to a Room key. Blobs are
 *    opaque CFE binary (`docs/FFI_BINARY_FORMAT.md`) and are never parsed here.
 *
 * 2. **`establishedAt` per peer** — storm-hardening invariant #6
 *    (`docs/ANDROID_ONBOARDING.md` §12): an END_SESSION older than the current
 *    session's establishment time is stale (ACK + drop). Hydrated via
 *    [getAllEstablishedAt] BEFORE queued control messages are processed.
 */
@Singleton
class SessionStateStore @Inject constructor(
    private val sessionStateDao: SessionStateDao,
    private val sessionMetaDao: SessionMetaDao,
) {

    /** Persist one CFE storage action without reconstructing a string key. */
    suspend fun saveSecureStore(slot: CfeSecureStoreSlot, cfeBytes: ByteArray) {
        val key = keyFor(slot)
        if (cfeBytes.isEmpty()) {
            sessionStateDao.delete(key)
        } else {
            saveSession(key, cfeBytes)
        }
        if (slot is CfeSecureStoreSlot.Session && cfeBytes.isNotEmpty() &&
            getEstablishedAt(slot.contactId) == null
        ) {
            setEstablishedAt(slot.contactId, System.currentTimeMillis())
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
        is CfeSecureStoreSlot.SessionArchive -> "archive:${slot.contactId}"
        CfeSecureStoreSlot.OrchestratorState -> ORCHESTRATOR_STATE_KEY
    }

    // ── establishedAt (stale END_SESSION filter) ─────────────────────────────

    suspend fun setEstablishedAt(contactId: String, establishedAtMs: Long) {
        sessionMetaDao.setEstablishedAt(SessionMetaEntity(contactId, establishedAtMs))
    }

    suspend fun getEstablishedAt(contactId: String): Long? =
        sessionMetaDao.getEstablishedAt(contactId)

    suspend fun getAllEstablishedAt(): Map<String, Long> =
        sessionMetaDao.getAll().associate { it.contactId to it.establishedAtMs }

    suspend fun removeMeta(contactId: String) = sessionMetaDao.delete(contactId)

    companion object {
        const val SESSION_KEY_PREFIX = "session:"
        const val ARCHIVE_KEY_PREFIX = "archive:"
        const val ORCHESTRATOR_STATE_KEY = "core:orchestrator-state"

        /** Keys of the slots the ML-KEM-768 layer had before PQXDH v2 — deleted on restore. */
        fun isLegacyPqKey(key: String): Boolean =
            key == "core:kyber-session-state" || key.startsWith("pq-deferred:") || key.startsWith("kyber-spk:")
    }
}
