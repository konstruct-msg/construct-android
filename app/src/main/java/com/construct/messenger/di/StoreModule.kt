package com.construct.messenger.di

import com.construct.messenger.data.local.DataStoreOrientationStore
import com.construct.messenger.data.local.OrientationStore
import com.construct.messenger.service.AndroidMediaPreviewText
import com.construct.messenger.service.MediaPreviewText
import com.construct.messenger.service.ContactAvatars
import com.construct.messenger.service.MediaContactAvatars
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

    @Binds
    abstract fun bindMediaPreviewText(impl: AndroidMediaPreviewText): MediaPreviewText

    @Binds
    abstract fun bindContactAvatars(impl: MediaContactAvatars): ContactAvatars
}
