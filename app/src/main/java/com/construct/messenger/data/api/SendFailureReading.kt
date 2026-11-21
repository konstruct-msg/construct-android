package com.construct.messenger.data.api

import io.grpc.Status
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import java.io.IOException
import uniffi.construct_core.MessageAfterSendFailure
import uniffi.construct_core.SendFailureKind
import uniffi.construct_core.afterSendFailure

/**
 * What a send that threw says about the message, as the core's rule takes it.
 *
 * The decision is construct-rules `send_failure`, the one iOS `SendFailure` calls: no answer — our
 * deadline, a connection that broke or was replaced under the call (`CANCELLED: Channel is
 * forcefully shutdown` when the stream is declared dead), a VEIL tunnel not up — waits for a path,
 * and the same ciphertext goes again; a status the server gave about the message fails it. Android
 * failed every one until 2026-11-21 (todo 321): a send cut by a reconnect showed red, the person
 * sent it again, and it arrived after the ones written later. This only reads the error.
 */
object SendFailureReading {

    fun kindOf(error: Throwable): SendFailureKind {
        val chain = generateSequence(error) { it.cause }.take(MAX_CAUSES).toList()
        chain.firstNotNullOfOrNull { it.grpcStatus() }?.let { return SendFailureKind.Status(it.code.value().toUInt()) }
        // A socket that failed or timed out under the call: nothing answered.
        if (chain.any { it is IOException }) return SendFailureKind.NoAnswer
        // Neither a status nor the path: ours, about the message.
        return SendFailureKind.Status(Status.Code.UNKNOWN.value().toUInt())
    }

    fun waitsForAPath(error: Throwable): Boolean = afterSendFailure(kindOf(error)) == MessageAfterSendFailure.QUEUED

    private fun Throwable.grpcStatus(): Status? = when (this) {
        is StatusException -> status
        is StatusRuntimeException -> status
        else -> null
    }

    private const val MAX_CAUSES = 8
}
