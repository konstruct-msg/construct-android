package com.construct.messenger.data.local

import android.content.Context
import android.util.Log
import uniffi.construct_core.LocalStore
import uniffi.construct_core.LocalStoreException
import java.io.File

/**
 * The app's one core store (TODO 136): `construct.db`, SQLCipher under [LocalStoreKey]. **Canon:**
 * iOS `LocalStoreSession`. Opened by [LocalStores], which decides whether it or Room answers.
 *
 * **No failure creates a store.** Each one means the data is probably still on this device and
 * something would not hand it over; a fresh empty store over it is how an account loses its
 * history without an error.
 */
class LocalStoreSession private constructor(val feed: LocalStoreFeed) {
    val store: LocalStore get() = feed.store

    sealed interface Failure {
        /** The Keystore did not hand the key over. Not opened, so not created either. */
        data object KeyUnavailable : Failure

        /** The file is there and the key is not its key. */
        data object WrongKey : Failure

        /** Written by a newer build; this one must not read or migrate it. */
        data object SchemaTooNew : Failure

        data class Storage(val message: String) : Failure
    }

    sealed interface Opening {
        class Opened(val session: LocalStoreSession) : Opening

        data class NotOpened(val failure: Failure) : Opening
    }

    /** Closes the store and deletes its files; every later call on [store] fails `Closed`. The key is the caller's. */
    fun wipe() = store.wipe()

    companion object {
        private const val TAG = "LocalStoreSession"

        /**
         * Beside the app's other files, not under `databases/`: Room's file there is already
         * called `construct.db`. Out of backups and device transfer, as everything is
         * (`data_extraction_rules.xml`) — the key it opens under does not travel.
         */
        fun defaultFile(context: Context): File = File(context.noBackupFilesDir, "construct.db")

        fun defaultKey(context: Context): LocalStoreKey =
            LocalStoreKey(File(context.noBackupFilesDir, "construct.db.key"), KeystoreKeyWrap())

        /** [key] is [LocalStoreKey.current]; null is its "the Keystore did not answer". Zeroed here. */
        fun open(file: File, key: ByteArray?): Opening {
            key ?: return Opening.NotOpened(Failure.KeyUnavailable)
            return try {
                Opening.Opened(LocalStoreSession(LocalStoreFeed(LocalStore(file.path, key))))
            } catch (e: LocalStoreException) {
                val failure = failureOf(e)
                Log.e(TAG, "not opened — $failure")
                Opening.NotOpened(failure)
            } finally {
                key.fill(0)
            }
        }

        fun failureOf(e: LocalStoreException): Failure = when (e) {
            is LocalStoreException.WrongKey -> Failure.WrongKey
            is LocalStoreException.SchemaTooNew -> Failure.SchemaTooNew
            is LocalStoreException.KeyLength,
            is LocalStoreException.Closed,
            is LocalStoreException.Storage,
            -> Failure.Storage(e.message ?: e.javaClass.simpleName)
        }
    }
}
