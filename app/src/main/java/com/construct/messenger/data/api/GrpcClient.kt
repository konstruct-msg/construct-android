package com.construct.messenger.data.api

import com.construct.messenger.data.auth.AuthInterceptor
import com.construct.messenger.transport.RouteObservingInterceptor
import com.construct.messenger.transport.TransportEvents
import com.construct.messenger.transport.TransportRoute
import io.grpc.ManagedChannel
import io.grpc.okhttp.OkHttpChannelBuilder
import shared.proto.sentinel.v1.SentinelServiceGrpcKt.SentinelServiceCoroutineStub
import shared.proto.services.v1.AuthServiceGrpcKt.AuthServiceCoroutineStub
import shared.proto.services.v1.DeviceServiceGrpcKt.DeviceServiceCoroutineStub
import shared.proto.services.v1.InviteServiceGrpcKt.InviteServiceCoroutineStub
import shared.proto.services.v1.KeyServiceGrpcKt.KeyServiceCoroutineStub
import shared.proto.services.v1.MediaServiceGrpcKt.MediaServiceCoroutineStub
import shared.proto.services.v1.MessagingServiceGrpcKt.MessagingServiceCoroutineStub
import shared.proto.services.v1.NotificationServiceGrpcKt.NotificationServiceCoroutineStub
import shared.proto.services.v1.UserServiceGrpcKt.UserServiceCoroutineStub
import shared.proto.services.v1.VeilServiceGrpcKt.VeilServiceCoroutineStub
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
 * | [sealedChannel] | **none** | `SendSealedMessage` (Stealth v2 — sender anonymity), `DownloadMedia` |
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
    private val transportEvents: TransportEvents,
) {

    /** The two channels and where they point. Swapped whole by [routeThrough], never field by field. */
    private class Channels(val auth: ManagedChannel, val sealed: ManagedChannel, val veilPort: Int?, val generation: Long)

    private var built = 0L

    @Volatile
    private var channels: Channels = build(veilPort = null)

    /** The local VEIL port traffic goes through, or null for the direct path. */
    val veilPort: Int? get() = channels.veilPort

    /**
     * Bumped whenever the channels are replaced. A call that fails after the number moved was cut
     * by the switch, not by the network, and is no evidence about the path.
     */
    val generation: Long get() = channels.generation

    /** Where calls go right now, as the route machine names it. */
    val target: TransportRoute.Target get() = channels.veilPort?.let { TransportRoute.Target.Veil(it, "") } ?: TransportRoute.Target.Direct

    /**
     * Point both channels at the local VEIL proxy ([port]) or back at the server (null).
     * Calls in flight on the old channels are cancelled — the stream reconnects on the new ones.
     * **Canon:** iOS `GRPCChannelManager.makeClient`: plaintext to 127.0.0.1, with the server's
     * host as `:authority`, because the backend routes on it.
     */
    fun routeThrough(port: Int?) {
        val old = synchronized(this) {
            if (channels.veilPort == port) return
            channels.also { channels = build(port) }
        }
        old.auth.shutdownNow()
        old.sealed.shutdownNow()
    }

    /** Fresh channels on the same route: the router's "invalidate the gRPC client". */
    fun reconnect() {
        val old = synchronized(this) { channels.also { channels = build(it.veilPort) } }
        old.auth.shutdownNow()
        old.sealed.shutdownNow()
    }

    private fun build(veilPort: Int?): Channels {
        fun base(): OkHttpChannelBuilder =
            if (veilPort == null) {
                OkHttpChannelBuilder.forAddress(HOST, PORT)
            } else {
                // The relay re-wraps this in TLS to the server; the hop to it is veil-TLS.
                OkHttpChannelBuilder.forAddress(LOOPBACK, veilPort).usePlaintext().overrideAuthority(HOST)
            }
        val via = veilPort?.let { TransportRoute.Target.Veil(it, "") } ?: TransportRoute.Target.Direct
        // Every unary outcome is evidence for Auto mode (TransportRouter).
        val observer = RouteObservingInterceptor(via, transportEvents)
        return Channels(
            auth = base().intercept(authInterceptor, observer).build(),
            // No AuthInterceptor — sealed sender RPCs must not carry sender identity
            // on the transport layer. The `SendSealedMessage` method is also in the
            // interceptor's unauthenticated whitelist as defence-in-depth.
            sealed = base().intercept(observer).build(),
            veilPort = veilPort,
            generation = ++built,
        )
    }

    // ── Authenticated stubs (auth channel) ─────────────────────────────────
    // Getters, not lazies: a stub is bound to a channel, and the channel changes with the route.
    // Building one is a wrapper allocation.

    val auth: AuthServiceCoroutineStub get() = AuthServiceCoroutineStub(channels.auth)
    val key: KeyServiceCoroutineStub get() = KeyServiceCoroutineStub(channels.auth)
    val device: DeviceServiceCoroutineStub get() = DeviceServiceCoroutineStub(channels.auth)
    val messaging: MessagingServiceCoroutineStub get() = MessagingServiceCoroutineStub(channels.auth)
    val user: UserServiceCoroutineStub get() = UserServiceCoroutineStub(channels.auth)
    val invite: InviteServiceCoroutineStub get() = InviteServiceCoroutineStub(channels.auth)
    val notification: NotificationServiceCoroutineStub get() = NotificationServiceCoroutineStub(channels.auth)
    val sentinel: SentinelServiceCoroutineStub get() = SentinelServiceCoroutineStub(channels.auth)
    val veil: VeilServiceCoroutineStub get() = VeilServiceCoroutineStub(channels.auth)

    // ── Sealed / unauthenticated stub (sealed channel) ─────────────────────

    /** Sealed-sender [MessagingServiceCoroutineStub] — **no** [AuthInterceptor].
     * Use for `SendSealedMessage` only (Stealth v2). */
    val sealedMessaging: MessagingServiceCoroutineStub get() = MessagingServiceCoroutineStub(channels.sealed)

    /**
     * [MediaServiceCoroutineStub] on the channel with no token — for `DownloadMedia` only, which
     * the server serves to anyone holding a media id. A token there would tell the server who
     * fetched each blob, and so who received whose upload; iOS sends one (TODO in the vault).
     */
    val publicMedia: MediaServiceCoroutineStub get() = MediaServiceCoroutineStub(channels.sealed)

    /** Authenticated media: minting an upload token and the upload itself. */
    val media: MediaServiceCoroutineStub get() = MediaServiceCoroutineStub(channels.auth)

    fun shutdown() {
        channels.auth.shutdown()
        channels.sealed.shutdown()
    }

    companion object {
        // Production gRPC backend (direct TLS — OkHttpChannelBuilder defaults to TLS).
        // Through VEIL both channels go to LOOPBACK instead ([routeThrough]).
        const val HOST = "ams.konstruct.cc"
        const val PORT = 443
        private const val LOOPBACK = "127.0.0.1"
    }
}
