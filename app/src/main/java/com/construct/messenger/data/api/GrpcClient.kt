package com.construct.messenger.data.api

import com.construct.messenger.data.auth.AuthInterceptor
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
 * Owns two [ManagedChannel] instances to the Konstrukt backend and exposes a grpc-kotlin
 * coroutine stub per service.
 *
 * ## Channel split
 *
 * | Channel | Auth | Used for |
 * |---------|------|---------|
 * | [authChannel] | [AuthInterceptor] | All authenticated RPCs (auth, key, user, messaging stream, …) |
 * | [sealedChannel] | **none** | `SendSealedMessage` only (Stealth v2 — sender anonymity) |
 *
 * The sealed channel is intentionally **separate** at the HTTP/2 connection level to prevent
 * the server from correlating sealed sends with the authenticated identity via connection
 * metadata (per Stealth v2 Phase 2 decision doc).
 *
 * Stub class names come from the generated `*OuterClassGrpcKt.kt` / `*GrpcKt.kt` files
 * under `app/build/generated/source/proto/debug/grpckt/` (see `app/src/main/proto/`).
 */
@Singleton
class GrpcClient @Inject constructor(
    private val authInterceptor: AuthInterceptor,
) {

    private val authChannel: ManagedChannel = OkHttpChannelBuilder
        .forAddress(HOST, PORT)
        .intercept(authInterceptor)
        .build()

    private val sealedChannel: ManagedChannel = OkHttpChannelBuilder
        .forAddress(HOST, PORT)
        // No AuthInterceptor — sealed sender RPCs must not carry sender identity
        // on the transport layer. The `SendSealedMessage` method is also in the
        // interceptor's unauthenticated whitelist as defence-in-depth.
        .build()

    // ── Authenticated stubs (authChannel) ──────────────────────────────────

    val auth: AuthServiceCoroutineStub by lazy { AuthServiceCoroutineStub(authChannel) }
    val key: KeyServiceCoroutineStub by lazy { KeyServiceCoroutineStub(authChannel) }
    val messaging: MessagingServiceCoroutineStub by lazy { MessagingServiceCoroutineStub(authChannel) }
    val user: UserServiceCoroutineStub by lazy { UserServiceCoroutineStub(authChannel) }
    val notification: NotificationServiceCoroutineStub by lazy { NotificationServiceCoroutineStub(authChannel) }
    val sentinel: SentinelServiceCoroutineStub by lazy { SentinelServiceCoroutineStub(authChannel) }

    // ── Sealed / unauthenticated stub (sealedChannel) ──────────────────────

    /** Sealed-sender [MessagingServiceCoroutineStub] — **no** [AuthInterceptor].
     * Use for `SendSealedMessage` only (Stealth v2). */
    val sealedMessaging: MessagingServiceCoroutineStub by lazy {
        MessagingServiceCoroutineStub(sealedChannel)
    }

    fun shutdown() {
        authChannel.shutdown()
        sealedChannel.shutdown()
    }

    private companion object {
        // Production gRPC backend (direct TLS — OkHttpChannelBuilder defaults to TLS,
        // no usePlaintext() call). Matches docs/IMPLEMENTATION_PLAN.md §5.1. VEIL-routed
        // fallback for censored networks is not wired up yet (Phase 5.1) — both channels
        // use the same host:port for now.
        const val HOST = "ams.konstruct.cc"
        const val PORT = 443
    }
}
