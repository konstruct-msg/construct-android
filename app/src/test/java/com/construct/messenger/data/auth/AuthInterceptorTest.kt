package com.construct.messenger.data.auth

import com.construct.messenger.data.local.KeystoreManager
import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.MethodDescriptor.Marshaller
import io.grpc.testing.TestMethodDescriptors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@Suppress("UNCHECKED_CAST")
class AuthInterceptorTest {

    private val keystoreManager: KeystoreManager = mock()
    private lateinit var interceptor: AuthInterceptor

    @Before
    fun setUp() {
        interceptor = AuthInterceptor(keystoreManager)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** A [Channel] that captures the [Metadata] passed to `ClientCall.start()`. */
    private class CapturingChannel : Channel() {
        var capturedHeaders: Metadata? = null

        override fun <ReqT, RespT> newCall(
            method: MethodDescriptor<ReqT, RespT>,
            callOptions: CallOptions,
        ): ClientCall<ReqT, RespT> {
            return object : ClientCall<ReqT, RespT>() {
                override fun start(
                    responseListener: Listener<RespT>,
                    headers: Metadata,
                ) {
                    capturedHeaders = headers
                }

                override fun sendMessage(message: ReqT) {}
                override fun halfClose() {}
                override fun cancel(message: String?, cause: Throwable?) {}
                override fun request(numMessages: Int) {}
            }
        }

        override fun authority(): String = "test"
    }

    /** Cache the void marshaller and cast to erase type param. */
    private val voidMarshaller: Marshaller<Any> =
        TestMethodDescriptors.voidMarshaller() as Marshaller<Any>

    private fun methodDescriptor(fullMethodName: String): MethodDescriptor<Any, Any> {
        return MethodDescriptor.newBuilder<Any, Any>()
            .setFullMethodName(fullMethodName)
            .setType(MethodDescriptor.MethodType.UNARY)
            .setRequestMarshaller(voidMarshaller)
            .setResponseMarshaller(voidMarshaller)
            .build()
    }

    /** Invokes the interceptor and returns the headers. */
    private fun intercept(methodName: String): Metadata {
        val channel = CapturingChannel()
        val call = interceptor.interceptCall(
            methodDescriptor("construct.MessagingService/$methodName"),
            CallOptions.DEFAULT,
            channel,
        ) as ClientCall<Any, Any>
        val headers = Metadata()
        val listener = object : ClientCall.Listener<Any>() {}
        call.start(listener, headers)
        return channel.capturedHeaders ?: error("start() was not called")
    }

    // ── Authenticated methods ─────────────────────────────────────────────────

    @Test
    fun `adds all three headers for authenticated method`() {
        whenever(keystoreManager.getAccessToken()).thenReturn("v4.public.abc123")
        whenever(keystoreManager.getUserId()).thenReturn("550e8400-e29b-41d4-a716-446655440000")
        whenever(keystoreManager.getDeviceId()).thenReturn("a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6")

        val headers = intercept("SendMessage")

        assertEquals("Bearer v4.public.abc123", headers[AUTHORIZATION])
        assertEquals("550e8400-e29b-41d4-a716-446655440000", headers[USER_ID])
        assertEquals("a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6", headers[DEVICE_ID])
    }

    @Test
    fun `adds headers for another authenticated method`() {
        whenever(keystoreManager.getAccessToken()).thenReturn("v4.public.xyz")
        whenever(keystoreManager.getUserId()).thenReturn("user-abc")
        whenever(keystoreManager.getDeviceId()).thenReturn("dev-123")

        val headers = intercept("UploadPreKeys")

        assertEquals("Bearer v4.public.xyz", headers[AUTHORIZATION])
        assertEquals("user-abc", headers[USER_ID])
        assertEquals("dev-123", headers[DEVICE_ID])
    }

    @Test
    fun `adds headers for Subscribe stream method`() {
        whenever(keystoreManager.getAccessToken()).thenReturn("v4.public.stream-token")
        whenever(keystoreManager.getUserId()).thenReturn("user-stream")
        whenever(keystoreManager.getDeviceId()).thenReturn("dev-stream")

        val headers = intercept("Subscribe")

        assertEquals("Bearer v4.public.stream-token", headers[AUTHORIZATION])
        assertEquals("user-stream", headers[USER_ID])
        assertEquals("dev-stream", headers[DEVICE_ID])
    }

    // ── Unauthenticated methods ──────────────────────────────────────────────

    @Test
    fun `skips headers for RegisterDevice`() {
        val headers = intercept("RegisterDevice")
        assertNull(headers[AUTHORIZATION])
        assertNull(headers[USER_ID])
        assertNull(headers[DEVICE_ID])
    }

    @Test
    fun `skips headers for AuthenticateDevice`() {
        val headers = intercept("AuthenticateDevice")
        assertNull(headers[AUTHORIZATION])
        assertNull(headers[USER_ID])
        assertNull(headers[DEVICE_ID])
    }

    @Test
    fun `skips headers for GetPowChallenge`() {
        val headers = intercept("GetPowChallenge")
        assertNull(headers[AUTHORIZATION])
        assertNull(headers[USER_ID])
        assertNull(headers[DEVICE_ID])
    }

    @Test
    fun `skips headers for RefreshToken`() {
        val headers = intercept("RefreshToken")
        assertNull(headers[AUTHORIZATION])
        assertNull(headers[USER_ID])
        assertNull(headers[DEVICE_ID])
    }

    @Test
    fun `skips headers for CheckUsernameAvailability`() {
        val headers = intercept("CheckUsernameAvailability")
        assertNull(headers[AUTHORIZATION])
        assertNull(headers[USER_ID])
        assertNull(headers[DEVICE_ID])
    }

    @Test
    fun `skips headers for SendSealedMessage`() {
        val headers = intercept("SendSealedMessage")
        assertNull(headers[AUTHORIZATION])
        assertNull(headers[USER_ID])
        assertNull(headers[DEVICE_ID])
    }

    // ── Edge cases ───────────────────────────────────────────────────────────

    @Test
    fun `skips Authorization header when token is null`() {
        whenever(keystoreManager.getAccessToken()).thenReturn(null)
        whenever(keystoreManager.getUserId()).thenReturn("user-ok")
        whenever(keystoreManager.getDeviceId()).thenReturn("dev-ok")

        val headers = intercept("SendMessage")

        assertNull("Authorization should be null when token is missing", headers[AUTHORIZATION])
        assertEquals("user-ok", headers[USER_ID])
        assertEquals("dev-ok", headers[DEVICE_ID])
    }

    @Test
    fun `skips x-user-id header when userId is null`() {
        whenever(keystoreManager.getAccessToken()).thenReturn("v4.public.token")
        whenever(keystoreManager.getUserId()).thenReturn(null)
        whenever(keystoreManager.getDeviceId()).thenReturn("dev-ok")

        val headers = intercept("SendMessage")

        assertEquals("Bearer v4.public.token", headers[AUTHORIZATION])
        assertNull("x-user-id should be null when userId is missing", headers[USER_ID])
        assertEquals("dev-ok", headers[DEVICE_ID])
    }

    @Test
    fun `skips x-device-id header when deviceId is null`() {
        whenever(keystoreManager.getAccessToken()).thenReturn("v4.public.token")
        whenever(keystoreManager.getUserId()).thenReturn("user-ok")
        whenever(keystoreManager.getDeviceId()).thenReturn(null)

        val headers = intercept("SendMessage")

        assertEquals("Bearer v4.public.token", headers[AUTHORIZATION])
        assertEquals("user-ok", headers[USER_ID])
        assertNull("x-device-id should be null when deviceId is missing", headers[DEVICE_ID])
    }

    @Test
    fun `all headers are null when all values are missing`() {
        whenever(keystoreManager.getAccessToken()).thenReturn(null)
        whenever(keystoreManager.getUserId()).thenReturn(null)
        whenever(keystoreManager.getDeviceId()).thenReturn(null)

        val headers = intercept("SendMessage")

        assertNull(headers[AUTHORIZATION])
        assertNull(headers[USER_ID])
        assertNull(headers[DEVICE_ID])
    }

    @Test
    fun `unauthenticated method with stored tokens still has no headers`() {
        whenever(keystoreManager.getAccessToken()).thenReturn("v4.public.secret")
        whenever(keystoreManager.getUserId()).thenReturn("user-1")
        whenever(keystoreManager.getDeviceId()).thenReturn("dev-1")

        val headers = intercept("RefreshToken")

        assertNull("Should skip headers even when tokens exist", headers[AUTHORIZATION])
        assertNull(headers[USER_ID])
        assertNull(headers[DEVICE_ID])
    }

    // ── Metadata key constants ────────────────────────────────────────────────

    private companion object {
        val AUTHORIZATION = Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER)
        val USER_ID = Metadata.Key.of("x-user-id", Metadata.ASCII_STRING_MARSHALLER)
        val DEVICE_ID = Metadata.Key.of("x-device-id", Metadata.ASCII_STRING_MARSHALLER)
    }
}
