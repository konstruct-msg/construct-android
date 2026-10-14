package com.construct.messenger.transport

import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.Status
import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** VEIL On never goes direct: the guard refuses without asking the channel for a call. */
class DirectPathGuardTest {

    private object Bytes : MethodDescriptor.Marshaller<ByteArray> {
        override fun stream(value: ByteArray): InputStream = ByteArrayInputStream(value)
        override fun parse(stream: InputStream): ByteArray = stream.readBytes()
    }

    private val method = MethodDescriptor.newBuilder(Bytes, Bytes)
        .setType(MethodDescriptor.MethodType.UNARY)
        .setFullMethodName("test.Service/Call")
        .build()

    private var asked = false
    private val channel = object : Channel() {
        override fun <ReqT, RespT> newCall(m: MethodDescriptor<ReqT, RespT>, options: CallOptions): ClientCall<ReqT, RespT> {
            asked = true
            throw AssertionError("the channel was asked for a call")
        }
        override fun authority() = "test"
    }

    @Test
    fun refusesWithoutTouchingTheChannelWhenDirectIsNotAllowed() {
        var closed: Status? = null
        val call = DirectPathGuard { false }.interceptCall(method, CallOptions.DEFAULT, channel)
        call.start(object : ClientCall.Listener<ByteArray>() {
            override fun onClose(status: Status, trailers: Metadata) { closed = status }
        }, Metadata())
        assertFalse(asked)
        assertEquals(Status.Code.UNAVAILABLE, closed?.code)
    }

    @Test
    fun passesThroughWhenDirectIsAllowed() {
        runCatching { DirectPathGuard { true }.interceptCall(method, CallOptions.DEFAULT, channel) }
        assertTrue(asked)
    }
}
