package com.construct.messenger.transport

import android.os.SystemClock
import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.ClientInterceptor
import io.grpc.ForwardingClientCall
import io.grpc.ForwardingClientCallListener
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.Status
import java.net.ConnectException

/**
 * Reports every unary RPC's outcome to the router: the evidence Auto mode decides on. The stream
 * is bidi and reports itself (`MessageStreamService`), so it is not counted twice here.
 * Canon: iOS `GRPCCallExecutor` posting `rpcSucceeded` / `rpcFailed` after `RPCFailureClassifier`.
 *
 * One instance per channel, built with the route that channel takes — [via] is fixed for its life.
 */
class RouteObservingInterceptor(
    private val via: TransportRoute.Target,
    private val events: TransportEvents,
) : ClientInterceptor {

    override fun <ReqT, RespT> interceptCall(
        method: MethodDescriptor<ReqT, RespT>,
        callOptions: CallOptions,
        next: Channel,
    ): ClientCall<ReqT, RespT> {
        val call = next.newCall(method, callOptions)
        if (method.type != MethodDescriptor.MethodType.UNARY) return call
        return object : ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(call) {
            override fun start(responseListener: Listener<RespT>, headers: Metadata) {
                val startedAt = SystemClock.elapsedRealtime()
                super.start(object : ForwardingClientCallListener.SimpleForwardingClientCallListener<RespT>(responseListener) {
                    override fun onClose(status: Status, trailers: Metadata) {
                        events.post(
                            if (status.isOk) {
                                TransportRoute.Event.RpcSucceeded(via, (SystemClock.elapsedRealtime() - startedAt).toInt())
                            } else {
                                TransportRoute.Event.RpcFailed(classify(status, via), via, foreground = true)
                            },
                        )
                        super.onClose(status, trailers)
                    }
                }, headers)
            }
        }
    }

    companion object {
        /**
         * A status into what it says about the path. Canon: iOS `RPCFailureClassifier` — an
         * answer from the server (auth, application errors) says the path works; a refused
         * loopback connection says the local proxy is gone.
         */
        fun classify(status: Status, via: TransportRoute.Target): TransportRoute.RpcFailure = when (status.code) {
            Status.Code.UNAUTHENTICATED, Status.Code.PERMISSION_DENIED -> TransportRoute.RpcFailure.AUTH_REJECTED
            Status.Code.CANCELLED -> TransportRoute.RpcFailure.TRANSIENT_CANCELLATION
            Status.Code.DEADLINE_EXCEEDED -> TransportRoute.RpcFailure.STREAM_TIMEOUT
            Status.Code.UNAVAILABLE, Status.Code.UNKNOWN, Status.Code.INTERNAL ->
                if (via is TransportRoute.Target.Veil && status.cause.isConnectRefused()) {
                    TransportRoute.RpcFailure.STALE_LOCAL_PROXY
                } else if (status.code == Status.Code.UNAVAILABLE || status.cause != null) {
                    TransportRoute.RpcFailure.TRANSPORT_UNKNOWN
                } else {
                    TransportRoute.RpcFailure.APPLICATION_ERROR
                }
            else -> TransportRoute.RpcFailure.APPLICATION_ERROR
        }

        private fun Throwable?.isConnectRefused(): Boolean {
            var t = this
            while (t != null) {
                if (t is ConnectException) return true
                t = t.cause
            }
            return false
        }
    }
}
