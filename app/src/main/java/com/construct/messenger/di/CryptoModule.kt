package com.construct.messenger.di

import com.construct.messenger.crypto.CryptoManager
import com.construct.messenger.service.OrchestratorGateway
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the crypto-layer interfaces consumed by the messaging pipeline.
 * [OrchestratorGateway] (CFE `handleEvent`) is served by the singleton
 * [CryptoManager], which owns the two-phase core + the serialization lock.
 *
 * `ProcessorEffects` is intentionally NOT bound here yet — it is implemented by
 * the repository/session layer, which lands with the send/persist wiring
 * (see `docs/GRPC_LAYER.md` §3.1).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CryptoModule {

    @Binds
    @Singleton
    abstract fun bindOrchestratorGateway(impl: CryptoManager): OrchestratorGateway
}
