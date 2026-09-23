package com.construct.messenger.data.local

import kotlinx.coroutines.flow.Flow

/**
 * Persists whether the user has completed the post-registration product
 * orientation (the "How Konstruct works" three-page guide).
 *
 * **Canon:** iOS `OrientationStore.completedKey` (`@AppStorage`). This is
 * non-sensitive UI state, so it lives in a plain DataStore-preferences file,
 * not the Keystore-backed `EncryptedSharedPreferences` used for tokens/keys
 * ([KeystoreManager]).
 */
interface OrientationStore {
    /** Emits `true` once the user has finished (or skipped) orientation at least once. */
    val completed: Flow<Boolean>

    /** Marks orientation as completed. Idempotent. */
    suspend fun setCompleted()
}
