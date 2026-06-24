package com.construct.messenger.data.api

import io.grpc.ManagedChannel
import io.grpc.okhttp.OkHttpChannelBuilder
import shared.proto.sentinel.v1.SentinelServiceGrpcKt.SentinelServiceCoroutineStub
import shared.proto.services.v1.AuthServiceGrpcKt.AuthServiceCoroutineStub
import shared.proto.services.v1.KeyServiceGrpcKt.KeyServiceCoroutineStub
import shared.proto.services.v1.MessagingServiceGrpcKt.MessagingServiceCoroutineStub
import shared.proto.services.v1.NotificationServiceGrpcKt.NotificationServiceCoroutineStub
import shared.proto.services.v1.UserServiceGrpcKt.UserServiceCoroutineStub
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the single [ManagedChannel] to the Konstrukt backend and exposes a grpc-kotlin
 * coroutine stub per service. Services to implement are tracked in
 * `docs/IMPLEMENTATION_PLAN.md` → Phase 2.3.
 *
 * Stub class names come from the generated `*OuterClassGrpcKt.kt` / `*GrpcKt.kt` files
 * under `app/build/generated/source/proto/debug/grpckt/` (see `app/src/main/proto/`).
 */
@Singleton
class GrpcClient @Inject constructor() {

    private val channel: ManagedChannel = OkHttpChannelBuilder
        .forAddress(HOST, PORT)
        .build()

    val auth: AuthServiceCoroutineStub by lazy { AuthServiceCoroutineStub(channel) }
    val key: KeyServiceCoroutineStub by lazy { KeyServiceCoroutineStub(channel) }
    val messaging: MessagingServiceCoroutineStub by lazy { MessagingServiceCoroutineStub(channel) }
    val user: UserServiceCoroutineStub by lazy { UserServiceCoroutineStub(channel) }
    val notification: NotificationServiceCoroutineStub by lazy { NotificationServiceCoroutineStub(channel) }
    val sentinel: SentinelServiceCoroutineStub by lazy { SentinelServiceCoroutineStub(channel) }

    fun shutdown() {
        channel.shutdown()
    }

    private companion object {
        // Production gRPC backend (direct TLS — OkHttpChannelBuilder defaults to TLS,
        // no usePlaintext() call). Matches docs/IMPLEMENTATION_PLAN.md §5.1. VEIL-routed
        // fallback for censored networks is not wired up yet (Phase 5.1) — this is the
        // direct path only.
        const val HOST = "ams.konstruct.cc"
        const val PORT = 443
    }
}
