package com.construct.messenger.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/**
 * Which of the contacts' devices the core has given a verdict (`todo/268`).
 *
 * [PeerDeviceStore] says a device was seen, which is what delivery needs. Paths that judge nothing
 * write it too, so it cannot also say a device is trusted. One meaning, one carrier: this is the
 * other meaning, read through [PeerDeviceRegistry.trusted]. **Canon:** iOS `PeerDeviceJudgement`.
 *
 * Holds device ids only, each the hash of a public key. No backup carries it
 * (`data_extraction_rules.xml`), and [LocalDataWipe] removes it with everything else.
 */
interface JudgedDeviceStore {
    suspend fun all(): Set<String>

    suspend fun add(deviceIds: Collection<String>)

    /**
     * [deviceIds] left [accountId]. A device that comes back is judged again, so it loses its mark;
     * those that had one replace [formerlyTrusted], which only has to say a verdict existed.
     */
    suspend fun retire(accountId: String, deviceIds: Collection<String>)

    /** The account's judged devices that have left it. Not empty: this is no first meeting. */
    suspend fun formerlyTrusted(accountId: String): Set<String>

    /** Whether the devices from before this store existed were marked. */
    suspend fun isSeeded(): Boolean

    /** Marks [deviceIds] and records that this was done, together. */
    suspend fun seed(deviceIds: Collection<String>)
}

private val Context.judgedDeviceDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "peer_device_judged")

class DataStoreJudgedDeviceStore(private val data: DataStore<Preferences>) : JudgedDeviceStore {
    @Inject
    constructor(@ApplicationContext context: Context) : this(context.judgedDeviceDataStore)

    override suspend fun all(): Set<String> = data.data.first()[JUDGED].orEmpty()

    override suspend fun add(deviceIds: Collection<String>) {
        if (deviceIds.isEmpty()) return
        data.edit { it[JUDGED] = it[JUDGED].orEmpty() + deviceIds }
    }

    override suspend fun retire(accountId: String, deviceIds: Collection<String>) {
        if (deviceIds.isEmpty()) return
        data.edit {
            val judged = it[JUDGED].orEmpty()
            val left = deviceIds.filter(judged::contains)
            it[JUDGED] = judged - deviceIds.toSet()
            if (left.isNotEmpty()) it[formerlyTrustedKey(accountId)] = left.toSet()
        }
    }

    override suspend fun formerlyTrusted(accountId: String): Set<String> =
        data.data.first()[formerlyTrustedKey(accountId)].orEmpty()

    override suspend fun isSeeded(): Boolean = data.data.first()[SEEDED] == true

    override suspend fun seed(deviceIds: Collection<String>) {
        data.edit {
            it[JUDGED] = it[JUDGED].orEmpty() + deviceIds
            it[SEEDED] = true
        }
    }

    private companion object {
        val JUDGED = stringSetPreferencesKey("judged")
        val SEEDED = booleanPreferencesKey("seeded_v1")

        fun formerlyTrustedKey(accountId: String) = stringSetPreferencesKey("formerly_trusted.$accountId")
    }
}
