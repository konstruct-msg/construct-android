package com.construct.messenger.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The peer devices whose session we opened from a signed prekey past the core's age limit (a
 * degraded open, `plan_stale_spk` → `OpenDegraded`), and that have not written to us since.
 *
 * **Why the platform keeps it:** the core opens the session and leaves the mark to the caller
 * (`init_session_allowing_stale`). **Canon:** iOS `KeychainManager.saveSessionAtRiskFlag`, per
 * device; `decisions/stale-peer-reachability.md`. Unlike iOS, the mark ends on an event and
 * never on a timer: the first message from that device has been decrypted (its reply ratchets past
 * the stale key), or a session with it was opened cleanly. iOS also re-keys on a schedule; Android
 * does not (todo/282).
 *
 * Holds device ids, each the hash of a public key, and the account each belongs to, for the chat
 * banner. No backup carries it (`data_extraction_rules.xml`), and [LocalDataWipe] removes it with
 * everything else.
 */
interface AtRiskSessionStore {
    /** Device id → account id of every device marked. */
    fun observe(): Flow<Map<String, String>>

    suspend fun mark(deviceId: String, accountId: String)

    /** Removes the mark; a device without one is not written. */
    suspend fun clear(deviceId: String)
}

private val Context.atRiskSessionDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "session_at_risk")

class DataStoreAtRiskSessionStore(private val data: DataStore<Preferences>) : AtRiskSessionStore {
    @Inject
    constructor(@ApplicationContext context: Context) : this(context.atRiskSessionDataStore)

    override fun observe(): Flow<Map<String, String>> = data.data.map { prefs ->
        prefs.asMap().mapNotNull { (key, value) ->
            val device = key.name.removePrefix(PREFIX).takeIf { key.name.startsWith(PREFIX) }
            if (device != null && value is String) device to value else null
        }.toMap()
    }

    override suspend fun mark(deviceId: String, accountId: String) {
        data.edit { it[key(deviceId)] = accountId }
    }

    override suspend fun clear(deviceId: String) {
        // Read first: this runs for every message decrypted, and almost none is marked.
        if (data.data.first()[key(deviceId)] == null) return
        data.edit { it.remove(key(deviceId)) }
    }

    private companion object {
        const val PREFIX = "device."

        fun key(deviceId: String) = stringPreferencesKey(PREFIX + deviceId)
    }
}
