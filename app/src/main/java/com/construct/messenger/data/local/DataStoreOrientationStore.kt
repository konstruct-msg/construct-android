package com.construct.messenger.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Process-wide DataStore for non-sensitive UI preferences. */
private val Context.orientationDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "orientation_prefs")

/**
 * DataStore-backed [OrientationStore]. Non-sensitive UI state — plain
 * DataStore-preferences, not the Keystore-backed store used for secrets.
 */
@Singleton
class DataStoreOrientationStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : OrientationStore {

    override val completed: Flow<Boolean> =
        context.orientationDataStore.data.map { prefs -> prefs[KEY_COMPLETED] ?: false }

    override suspend fun setCompleted() {
        context.orientationDataStore.edit { prefs -> prefs[KEY_COMPLETED] = true }
    }

    private companion object {
        val KEY_COMPLETED = booleanPreferencesKey("orientation_completed")
    }
}
