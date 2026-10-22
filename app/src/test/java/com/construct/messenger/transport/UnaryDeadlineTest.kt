package com.construct.messenger.transport

import io.grpc.CallOptions
import io.grpc.Deadline
import io.grpc.ManagedChannel
import io.grpc.MethodDescriptor
import io.grpc.MethodDescriptor.MethodType
import io.grpc.Server
import io.grpc.ServerServiceDefinition
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.kotlin.ClientCalls
import io.grpc.stub.ServerCalls
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** A unary call that nobody answers ends; a stream and a caller's own deadline are left alone. */
class UnaryDeadlineTest {

    private object Bytes : MethodDescriptor.Marshaller<ByteArray> {
        override fun stream(value: ByteArray): InputStream = ByteArrayInputStream(value)
        override fun parse(stream: InputStream): ByteArray = stream.readBytes()
    }

    private fun method(type: MethodType) = MethodDescriptor.newBuilder(Bytes, Bytes)
        .setType(type)
        .setFullMethodName("test.Silent/${type.name}")
        .build()

    private var server: Server? = null
    private var channel: ManagedChannel? = null

    @After
    fun tearDown() {
        channel?.shutdownNow()
        server?.shutdownNow()
    }

    /** The tunnel that swallowed the call: the server never answers. Mutation: pass `callOptions` through unchanged — hangs until the timeout. */
    @Test(timeout = 10_000)
    fun aUnaryCallNobodyAnswersEndsDeadlineExceeded() = runBlocking {
        val unary = method(MethodType.UNARY)
        val name = InProcessServerBuilder.generateName()
        server = InProcessServerBuilder.forName(name)
            .addService(ServerServiceDefinition.builder("test.Silent").addMethod(unary, ServerCalls.asyncUnaryCall { _, _ -> }).build())
            .build().start()
        channel = InProcessChannelBuilder.forName(name).intercept(UnaryDeadline(seconds = 1)).build()

        val startedAt = System.nanoTime()
        try {
            ClientCalls.unaryRpc(channel!!, unary, ByteArray(0))
            fail("a call nobody answers returned")
        } catch (e: StatusException) {
            assertEquals(Status.Code.DEADLINE_EXCEEDED, e.status.code)
        }
        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startedAt) < 5)
    }

    @Test
    fun streamsGetNoDeadline() {
        for (type in listOf(MethodType.BIDI_STREAMING, MethodType.SERVER_STREAMING, MethodType.CLIENT_STREAMING)) {
            assertNull(UnaryDeadline.withDefaultDeadline(type, CallOptions.DEFAULT, 30).deadline)
        }
    }

    @Test
    fun aCallersOwnDeadlineIsKept() {
        val own = CallOptions.DEFAULT.withDeadline(Deadline.after(300, TimeUnit.SECONDS))
        assertSame(own, UnaryDeadline.withDefaultDeadline(MethodType.UNARY, own, 30))
    }

    @Test
    fun aUnaryCallWithoutOneGetsTheDefault() {
        val deadline = UnaryDeadline.withDefaultDeadline(MethodType.UNARY, CallOptions.DEFAULT, 30).deadline!!
        val left = deadline.timeRemaining(TimeUnit.SECONDS)
        assertTrue("$left s left", left in 28..30)
    }
}
