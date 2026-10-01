package com.construct.messenger.di

import com.construct.messenger.calls.CallMedia
import com.construct.messenger.calls.CallPeers
import com.construct.messenger.calls.CallPeersImpl
import com.construct.messenger.calls.CallSignalPort
import com.construct.messenger.calls.CallSignalTransport
import com.construct.messenger.calls.CallSignalingPort
import com.construct.messenger.calls.SignalingClient
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class CallsModule {
    @Binds
    abstract fun bindSignals(transport: CallSignalTransport): CallSignalPort

    @Binds
    abstract fun bindSignaling(client: SignalingClient): CallSignalingPort

    @Binds
    abstract fun bindPeers(peers: CallPeersImpl): CallPeers

    companion object {
        /**
         * No media yet: C2 step 4 puts webrtc-sdk behind [CallMedia]. Until then a call can ring
         * and be declined, and answering ends it with "Accept failed" — nothing on screen can
         * answer one anyway (the call screen is step 5).
         */
        @Provides
        fun mediaFactory(): CallMedia.Factory = CallMedia.Factory { _, _, _ -> error("call media is C2 step 4") }
    }
}
