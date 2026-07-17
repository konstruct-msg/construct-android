package com.construct.messenger.data.local

import com.construct.messenger.data.local.db.SessionMetaDao
import com.construct.messenger.data.local.db.SessionMetaEntity
import com.construct.messenger.data.local.db.SessionStateDao
import com.construct.messenger.data.local.db.SessionStateEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistence for CFE session bytes and per-peer session metadata.
 *
 * Two distinct concerns:
 *
 * 1. **Session blobs** — the Rust core's `CfeAction.SaveSessionToSecureStore(key, data)`
 *    lands here via `ProcessorEffects.saveSession`. Blobs are opaque CFE binary
 *    (`docs/FFI_BINARY_FORMAT.md`); they are loaded back and handed to
 *    `CryptoManager.importSessionBytes` on startup so Double-Ratchet sessions
 *    survive process restarts.
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

    // ── establishedAt (stale END_SESSION filter) ─────────────────────────────

    suspend fun setEstablishedAt(contactId: String, establishedAtMs: Long) {
        sessionMetaDao.setEstablishedAt(SessionMetaEntity(contactId, establishedAtMs))
    }

    suspend fun getEstablishedAt(contactId: String): Long? =
        sessionMetaDao.getEstablishedAt(contactId)

    suspend fun getAllEstablishedAt(): Map<String, Long> =
        sessionMetaDao.getAll().associate { it.contactId to it.establishedAtMs }

    suspend fun removeMeta(contactId: String) = sessionMetaDao.delete(contactId)
}
