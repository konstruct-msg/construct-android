package com.construct.messenger.di

import com.construct.messenger.data.local.DataStoreOrientationStore
import com.construct.messenger.data.local.OrientationStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Bindings for local, non-sensitive preference stores. */
@Module
@InstallIn(SingletonComponent::class)
abstract class StoreModule {
    @Binds
    @Singleton
    abstract fun bindOrientationStore(
        store: DataStoreOrientationStore
    ): OrientationStore
}
