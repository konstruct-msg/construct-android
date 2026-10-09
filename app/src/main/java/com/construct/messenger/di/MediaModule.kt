package com.construct.messenger.di

import com.construct.messenger.media.ExoVideoNoteEngine
import com.construct.messenger.media.MediaArrivals
import com.construct.messenger.media.MediaPrefetcher
import com.construct.messenger.media.VideoNotePlayback
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class MediaModule {
    @Binds
    abstract fun bindVideoNoteEngine(engine: ExoVideoNoteEngine): VideoNotePlayback.Engine

    @Binds
    abstract fun bindMediaArrivals(prefetcher: MediaPrefetcher): MediaArrivals
}
