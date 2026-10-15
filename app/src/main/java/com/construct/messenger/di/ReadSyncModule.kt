package com.construct.messenger.di

import com.construct.messenger.domain.usecase.SendMessageUseCase
import com.construct.messenger.readsync.ReadState
import com.construct.messenger.readsync.ReadSyncOutbox
import com.construct.messenger.readsync.ReadSyncState
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Read state synced between this account's own devices (TODO 153). */
@Module
@InstallIn(SingletonComponent::class)
abstract class ReadSyncModule {
    @Binds
    abstract fun bindReadState(state: ReadSyncState): ReadState

    companion object {
        /** Markers go to our own other devices only; [SendMessageUseCase.sendReadMarkers] has no other addressee. */
        @Provides
        fun provideSender(send: dagger.Lazy<SendMessageUseCase>): ReadSyncOutbox.Sender =
            ReadSyncOutbox.Sender { payload -> send.get().sendReadMarkers(payload) }
    }
}
