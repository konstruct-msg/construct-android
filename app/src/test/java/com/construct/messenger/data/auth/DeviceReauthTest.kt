package com.construct.messenger.data.auth

import com.construct.messenger.crypto.CryptoManager
import com.construct.messenger.data.api.GrpcClient
import com.construct.messenger.data.local.KeystoreManager
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import shared.proto.services.v1.AuthServiceGrpcKt.AuthServiceCoroutineStub
import shared.proto.services.v1.AuthServiceOuterClass.AuthTokensResponse
import shared.proto.services.v1.AuthServiceOuterClass.AuthenticateDeviceResponse

/** TODO 171: a device whose refresh token was refused signs in again with its key. **Canon:** iOS `DeviceAuthCoordinator`. */
class DeviceReauthTest {
    private val cryptoManager: CryptoManager = mock()
    private val grpcClient: GrpcClient = mock()
    private val keystore: KeystoreManager = mock()
    private val authStub: AuthServiceCoroutineStub = mock()
    private val removal = DeviceRemoval()
    private var now = 1_000_000L
    private val reauth = DeviceReauth(cryptoManager, grpcClient, keystore, removal) { now }

    private val tokens = AuthTokensResponse.newBuilder().setUserId(USER).setAccessToken("new").setRefreshToken("r").build()

    @Before
    fun setUp() {
        whenever(grpcClient.auth).thenReturn(authStub)
        whenever(grpcClient.veilPort).thenReturn(null)
        whenever(cryptoManager.signWithDeviceKey(any())).thenReturn(byteArrayOf(1))
        whenever(keystore.getAccessToken()).thenReturn(OLD)
        whenever(keystore.getDeviceId()).thenReturn(DEVICE)
        whenever(keystore.getUserId()).thenReturn(USER)
    }

    private suspend fun answers() {
        whenever(authStub.authenticateDevice(any(), any())).thenReturn(AuthenticateDeviceResponse.newBuilder().setTokens(tokens).build())
    }

    /** The tester's case. Mutation: drop the save — nothing changes on the device. */
    @Test
    fun `a refused refresh is followed by a sign-in that keeps its tokens`() = runTest {
        answers()
        assertTrue(reauth.recover(OLD))
        verify(keystore).saveTokens(tokens, DEVICE)
        // The running core signs; a new bootstrap core beside the orchestrator would be wrong.
        verify(cryptoManager, never()).loadOrCreate(any())
    }

    /** Mutation: skip the check — every racer after the first signs in again. */
    @Test
    fun `a token replaced meanwhile is not signed in again`() = runTest {
        answers()
        whenever(keystore.getAccessToken()).thenReturn("replaced")
        assertTrue(reauth.recover(OLD))
        verify(authStub, never()).authenticateDevice(any(), any())
    }

    /** Mutation: no interval — every stream retry and VEIL start would sign in. */
    @Test
    fun `at most one attempt a minute`() = runTest {
        whenever(authStub.authenticateDevice(any(), any())).thenThrow(io.grpc.Status.UNAVAILABLE.asRuntimeException())
        assertFalse(reauth.recover(OLD))
        now += 59_000
        assertFalse(reauth.recover(OLD))
        verify(authStub, times(1)).authenticateDevice(any(), any())
        now += 1_000
        assertFalse(reauth.recover(OLD))
        verify(authStub, times(2)).authenticateDevice(any(), any())
    }

    /** Mutation: wipe before the call — a refusal a relay forged would cost the session. */
    @Test
    fun `a failed sign-in keeps the stored tokens`() = runTest {
        whenever(authStub.authenticateDevice(any(), any())).thenThrow(io.grpc.Status.UNAUTHENTICATED.asRuntimeException())
        assertFalse(reauth.recover(OLD))
        verify(keystore, never()).clearTokens()
        verify(keystore, never()).saveTokens(any(), any())
    }

    @Test
    fun `tokens for another account are not taken`() = runTest {
        answers()
        whenever(keystore.getUserId()).thenReturn("someone-else")
        assertFalse(reauth.recover(OLD))
        verify(keystore, never()).saveTokens(any(), any())
    }

    /** Mutation: drop the `heard` — a removed device lives on with every call refused. */
    @Test
    fun `a removal heard over direct TLS erases the device`() = runTest {
        whenever(authStub.authenticateDevice(any(), any())).thenThrow(refusedWith("1"))
        assertFalse(reauth.recover(OLD))
        assertTrue(removal.removed.value)
    }

    @Test
    fun `a removal heard through VEIL does not`() = runTest {
        whenever(grpcClient.veilPort).thenReturn(40_000)
        whenever(authStub.authenticateDevice(any(), any())).thenThrow(refusedWith("1"))
        assertFalse(reauth.recover(OLD))
        assertFalse(removal.removed.value)
    }

    private fun refusedWith(number: String): io.grpc.StatusRuntimeException =
        io.grpc.Status.UNAUTHENTICATED.withDescription("Device is inactive").asRuntimeException(
            io.grpc.Metadata().apply {
                put(io.grpc.Metadata.Key.of("construct-device-refusal", io.grpc.Metadata.ASCII_STRING_MARSHALLER), number)
            },
        )

    private companion object {
        const val OLD = "v4.public.old"
        const val DEVICE = "device-1"
        const val USER = "user-1"
    }
}
