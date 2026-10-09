package com.construct.messenger.data.local

import uniffi.construct_core.LocalStore
import uniffi.construct_core.LocalStoreChange
import uniffi.construct_core.LocalStoreObserver
import uniffi.construct_core.LocalStoreTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onSubscription

/**
 * The core store's changes as a `Flow` — what Room's observable queries are to the Room stores.
 * The core tells its one observer after every committed write, on the writing thread
 * ([LocalStoreObserver]); this is that observer.
 *
 * A change only says which rows of which table moved; a watcher re-reads its query. When a slow
 * watcher falls behind, older changes are dropped, never the latest — re-reading after the latest
 * one sees every write before it.
 */
class LocalStoreFeed(val store: LocalStore) : LocalStoreObserver {
    private val changes = MutableSharedFlow<LocalStoreChange>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    init {
        store.setObserver(this)
    }

    override fun onChange(change: LocalStoreChange) {
        changes.tryEmit(change)
    }

    /** [query] now, and again after every change to [table]; equal answers are not repeated. */
    fun <T> watch(table: LocalStoreTable, query: (LocalStore) -> T): Flow<T> =
        changes
            // Registered before the first read, so a write between the two is not missed.
            .onSubscription { emit(LocalStoreChange(table, emptyList())) }
            .filter { it.table == table }
            .map { query(store) }
            .distinctUntilChanged()
            .flowOn(Dispatchers.IO)
}
