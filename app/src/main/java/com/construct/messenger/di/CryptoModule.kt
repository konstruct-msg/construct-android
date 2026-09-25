package com.construct.messenger.di

import com.construct.messenger.crypto.CryptoManager
import com.construct.messenger.service.IncomingAlerts
import com.construct.messenger.service.MessageNotifier
import com.construct.messenger.service.OrchestratorGateway
import com.construct.messenger.service.ProcessorEffects
import com.construct.messenger.service.ProcessorEffectsImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the crypto-layer interfaces consumed by the messaging pipeline.
 * [OrchestratorGateway] (CFE `handleEvent`) is served by the singleton
 * [CryptoManager], which owns the two-phase core + the serialization lock.
 * [ProcessorEffects] is [ProcessorEffectsImpl] (Room persist + ACK + session blobs).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CryptoModule {

    @Binds
    @Singleton
    abstract fun bindOrchestratorGateway(impl: CryptoManager): OrchestratorGateway

    @Binds
    @Singleton
    abstract fun bindProcessorEffects(impl: ProcessorEffectsImpl): ProcessorEffects

    @Binds
    @Singleton
    abstract fun bindIncomingAlerts(impl: MessageNotifier): IncomingAlerts
}
