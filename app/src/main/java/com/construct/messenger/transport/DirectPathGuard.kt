package com.construct.messenger.transport

import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.ClientInterceptor
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.Status

/**
 * On a channel that goes straight to the server, refuses every call while VEIL is On: the call
 * closes UNAVAILABLE before the channel is asked for a connection, so nothing is dialled outside
 * the tunnel. Asked per call, not per channel — the mode can change while a direct channel lives.
 * Canon: iOS `GRPCChannelManager.makeClient`/`makeSealedClient` throwing `VeilTunnelNotUp`
 * (vault `decisions/veil-on-never-goes-direct.md`).
 *
 * Installed outermost, so neither the token interceptor nor the route observer sees the call: the
 * refusal says nothing about the path, and a token refresh would be a direct call of its own.
 */
class DirectPathGuard(private val allowsDirectPath: () -> Boolean) : ClientInterceptor {

    override fun <ReqT, RespT> interceptCall(
        method: MethodDescriptor<ReqT, RespT>,
        callOptions: CallOptions,
        next: Channel,
    ): ClientCall<ReqT, RespT> =
        if (allowsDirectPath()) next.newCall(method, callOptions) else Refused()

    private class Refused<ReqT, RespT> : ClientCall<ReqT, RespT>() {
        override fun start(responseListener: Listener<RespT>, headers: Metadata) {
            responseListener.onClose(STATUS, Metadata())
        }
        override fun request(numMessages: Int) = Unit
        override fun cancel(message: String?, cause: Throwable?) = Unit
        override fun halfClose() = Unit
        override fun sendMessage(message: ReqT) = Unit
    }

    companion object {
        val STATUS: Status = Status.UNAVAILABLE.withDescription("VEIL is on and no tunnel is up — not connecting directly")
    }
}
