package com.construct.messenger.transport

import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.ClientInterceptor
import io.grpc.MethodDescriptor
import java.util.concurrent.TimeUnit

/**
 * Gives every unary RPC that set no deadline of its own one of [seconds]. Until 2026-10-22 none
 * had one: a call on a connection a tunnel had silently dropped hung until the connection did —
 * seen under a whitelist-bypass VPN (Portal WG), an AUTH call answered after 71 min and a sealed
 * send after 6 min, holding up everything waiting on them. A call that runs out closes
 * DEADLINE_EXCEEDED, which the route observer reads as a transport failure, and the caller's own
 * retry takes it from there. Streams are left alone: they live as long as the app is connected,
 * and the stream watchdog (`MessageStreamService`) is what notices a dead one. A caller that set
 * its own deadline (media up- and download) keeps it.
 *
 * Canon: iOS gives each call its own timeout (`NetworkTiming.GRPC`, 15–30 s).
 */
class UnaryDeadline(private val seconds: Long = DEFAULT_SECONDS) : ClientInterceptor {

    override fun <ReqT, RespT> interceptCall(
        method: MethodDescriptor<ReqT, RespT>,
        callOptions: CallOptions,
        next: Channel,
    ): ClientCall<ReqT, RespT> = next.newCall(method, withDefaultDeadline(method.type, callOptions, seconds))

    companion object {
        const val DEFAULT_SECONDS = 30L

        internal fun withDefaultDeadline(type: MethodDescriptor.MethodType, options: CallOptions, seconds: Long): CallOptions =
            if (type != MethodDescriptor.MethodType.UNARY || options.deadline != null) options
            else options.withDeadlineAfter(seconds, TimeUnit.SECONDS)
    }
}
