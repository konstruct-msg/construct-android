package com.construct.messenger.di

import com.construct.messenger.calls.CallMedia
import com.construct.messenger.calls.CallPeers
import com.construct.messenger.calls.CallPeersImpl
import com.construct.messenger.calls.CallSignalPort
import com.construct.messenger.calls.CallSignalTransport
import com.construct.messenger.calls.CallSignalingPort
import com.construct.messenger.calls.SignalingClient
import com.construct.messenger.calls.WebRtcCallMedia
import dagger.Binds
import dagger.Module
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

    @Binds
    abstract fun bindMedia(factory: WebRtcCallMedia.Factory): CallMedia.Factory
}
