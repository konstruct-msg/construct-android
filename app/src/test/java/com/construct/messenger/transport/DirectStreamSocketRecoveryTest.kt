package com.construct.messenger.transport

import com.construct.messenger.transport.TransportRoute.Effect
import com.construct.messenger.transport.TransportRoute.Event
import com.construct.messenger.transport.TransportRoute.Mode
import com.construct.messenger.transport.TransportRoute.State
import com.construct.messenger.transport.TransportRoute.StreamFailure
import com.construct.messenger.transport.TransportRoute.StreamMethod
import com.construct.messenger.transport.TransportRoute.Target
import io.grpc.CallOptions
import io.grpc.InsecureServerCredentials
import io.grpc.ManagedChannel
import io.grpc.MethodDescriptor
import io.grpc.ServerServiceDefinition
import io.grpc.Status
import io.grpc.okhttp.OkHttpChannelBuilder
import io.grpc.okhttp.OkHttpServerBuilder
import io.grpc.stub.ClientCalls
import io.grpc.stub.ServerCalls
import io.grpc.stub.StreamObserver
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real OkHttp HTTP/2 sockets; the proxy swallows packets on the old TCP connection only. */
class DirectStreamSocketRecoveryTest {
    private object Bytes : MethodDescriptor.Marshaller<ByteArray> {
        override fun stream(value: ByteArray): InputStream = ByteArrayInputStream(value)
        override fun parse(stream: InputStream): ByteArray = stream.readBytes()
    }

    private val method = MethodDescriptor.newBuilder(Bytes, Bytes)
        .setType(MethodDescriptor.MethodType.BIDI_STREAMING)
        .setFullMethodName("test.Vpn/Stream")
        .build()

    /** Same transport as Android, without VPN software, TLS, authentication or production data. */
    @Test(timeout = 15_000)
    fun autoUnderVpnReceivesAgainAfterTheOldSocketStopsCarryingPackets() {
        val service = ServerServiceDefinition.builder("test.Vpn")
            .addMethod(method, ServerCalls.asyncBidiStreamingCall<ByteArray, ByteArray> { replies ->
                object : StreamObserver<ByteArray> {
                    override fun onNext(value: ByteArray) = replies.onNext(value)
                    override fun onError(t: Throwable) = Unit
                    override fun onCompleted() = replies.onCompleted()
                }
            }).build()
        val server = OkHttpServerBuilder.forPort(0, InsecureServerCredentials.create()).addService(service).build().start()
        try {
            PacketDropProxy(server.port).use { proxy ->
                fun connect() = OkHttpChannelBuilder.forAddress("127.0.0.1", proxy.port).usePlaintext().build()
                var channel = connect()
                try {
                    val frame = byteArrayOf(7)
                    assertArrayEquals(frame, receive(channel, frame).get(3, TimeUnit.SECONDS))
                    assertEquals(1, proxy.connections.get())

                    proxy.dropOldConnection.set(true)
                    val failed = receive(channel, frame).handle { _, error -> Status.fromThrowable(error).code }.get(3, TimeUnit.SECONDS)
                    assertEquals(Status.Code.DEADLINE_EXCEEDED, failed)
                    assertEquals("a new gRPC stream reused the blackholed TCP connection", 1, proxy.connections.get())

                    val timeout = Event.StreamFailed(StreamMethod.H2, StreamFailure.OPEN_TIMEOUT, Target.Direct)
                    val outcome = routeOutcome(State.Direct(2), timeout, Mode.AUTO, underVpn = true, nowMs = 0)
                    assertTrue(outcome.state is State.Direct)
                    assertEquals(listOf(Effect.InvalidateGrpcClient), outcome.effects)
                    // TransportRouter applies this effect by replacing GrpcClient's channels.
                    for (effect in outcome.effects) {
                        if (effect == Effect.InvalidateGrpcClient) {
                            channel.shutdownNow()
                            channel = connect()
                        }
                    }

                    assertArrayEquals(frame, receive(channel, frame).get(3, TimeUnit.SECONDS))
                    assertEquals("recovery opened a new TCP connection", 2, proxy.connections.get())
                } finally {
                    channel.shutdownNow()
                }
            }
        } finally {
            server.shutdownNow().awaitTermination(3, TimeUnit.SECONDS)
        }
    }

    private fun receive(channel: ManagedChannel, frame: ByteArray): CompletableFuture<ByteArray> {
        val result = CompletableFuture<ByteArray>()
        val call = channel.newCall(method, CallOptions.DEFAULT.withDeadlineAfter(1, TimeUnit.SECONDS))
        ClientCalls.asyncBidiStreamingCall(call, object : StreamObserver<ByteArray> {
            override fun onNext(value: ByteArray) {
                result.complete(value)
                call.cancel("frame received", null)
            }
            override fun onError(t: Throwable) { result.completeExceptionally(t) }
            override fun onCompleted() { result.completeExceptionally(IllegalStateException("no frame")) }
        }).onNext(frame)
        return result
    }

    private class PacketDropProxy(private val upstreamPort: Int) : AutoCloseable {
        private val listener = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        private val workers = Executors.newCachedThreadPool()
        private val sockets = CopyOnWriteArrayList<Socket>()
        val connections = AtomicInteger()
        val dropOldConnection = AtomicBoolean()
        val port: Int get() = listener.localPort

        init {
            workers.execute {
                try {
                    while (!listener.isClosed) {
                        val client = listener.accept().also { sockets += it }
                        val upstream = Socket("127.0.0.1", upstreamPort).also { sockets += it }
                        val connection = connections.incrementAndGet()
                        forward(client, upstream, connection)
                        forward(upstream, client, connection)
                    }
                } catch (_: java.io.IOException) {
                    // Closing the fixture interrupts accept/read.
                }
            }
        }

        private fun forward(from: Socket, to: Socket, connection: Int) = workers.execute {
            try {
                val buffer = ByteArray(8192)
                val input = from.getInputStream()
                val output = to.getOutputStream()
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (connection != 1 || !dropOldConnection.get()) {
                        output.write(buffer, 0, n)
                        output.flush()
                    }
                }
            } catch (_: java.io.IOException) {
                // Closing the fixture interrupts accept/read.
            }
        }

        override fun close() {
            listener.close()
            sockets.forEach { it.close() }
            workers.shutdownNow()
            workers.awaitTermination(3, TimeUnit.SECONDS)
        }
    }
}
