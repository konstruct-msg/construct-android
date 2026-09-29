package com.construct.messenger.transport

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * What happened on the wire, posted by whoever saw it (the gRPC interceptor, the stream) and read
 * by [TransportRouter] alone. A bus rather than a call: the observers sit under `GrpcClient`, and
 * the router drives `GrpcClient`, so a direct reference would be a cycle.
 */
@Singleton
class TransportEvents @Inject constructor() {
    private val flow = MutableSharedFlow<TransportRoute.Event>(
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<TransportRoute.Event> = flow.asSharedFlow()

    fun post(event: TransportRoute.Event) {
        flow.tryEmit(event)
    }
}
