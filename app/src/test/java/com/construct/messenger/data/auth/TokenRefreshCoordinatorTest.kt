package com.construct.messenger.data.auth

import com.construct.messenger.data.api.GrpcClient
import com.construct.messenger.data.local.KeystoreManager
import io.grpc.Status
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
import shared.proto.services.v1.AuthServiceOuterClass.RefreshTokenRequest
import shared.proto.services.v1.AuthServiceOuterClass.RefreshTokenResponse


class TokenRefreshCoordinatorTest {

    private val keystoreManager: KeystoreManager = mock()
    private val grpcClient: GrpcClient = mock()
    private val authStub: AuthServiceCoroutineStub = mock()

    private lateinit var coordinator: TokenRefreshCoordinator
    private val removal = DeviceRemoval()

    @Before
    fun setUp() {
        whenever(grpcClient.auth).thenReturn(authStub)
        // Direct TLS unless a test says otherwise; Mockito would answer 0 — a VEIL port.
        whenever(grpcClient.veilPort).thenReturn(null)
        coordinator = TokenRefreshCoordinator(keystoreManager, grpcClient, removal)
    }

    // ── Success ─────────────────────────────────────────────────────────────

    @Test
    fun `refresh available tokens returns success and persists new tokens`() = runTest {
        val response = RefreshTokenResponse.newBuilder()
            .setAccessToken("new_access_token")
            .setRefreshToken("rotated_refresh_token")
            .setExpiresAt(1_800_000_000L)
            .build()
        whenever(keystoreManager.getRefreshToken()).thenReturn("old_refresh_token")
        whenever(keystoreManager.getDeviceId()).thenReturn("device-1")
        whenever(authStub.refreshToken(any(), any())).thenReturn(response)

        val result = coordinator.refreshIfPossible()

        val success = result as? TokenRefreshResult.Success
        assertNotNull("Expected Success", success)
        assertEquals("new_access_token", success!!.accessToken)
        assertEquals("rotated_refresh_token", success.refreshToken)
        assertEquals(1_800_000_000L, success.expiresAt)

        verify(keystoreManager).saveAccessToken("new_access_token")
        verify(keystoreManager).saveRefreshToken("rotated_refresh_token")
    }

    @Test
    fun `refresh without rotated refresh token skips saving it`() = runTest {
        // `refresh_token` is optional in the proto — server may not rotate.
        val response = RefreshTokenResponse.newBuilder()
            .setAccessToken("new_access_token")
            .setExpiresAt(1_800_000_000L)
            .build()
        whenever(keystoreManager.getRefreshToken()).thenReturn("old_refresh_token")
        whenever(keystoreManager.getDeviceId()).thenReturn("device-1")
        whenever(authStub.refreshToken(any(), any())).thenReturn(response)

        val result = coordinator.refreshIfPossible()

        val success = result as? TokenRefreshResult.Success
        assertNotNull("Expected Success", success)
        assertNull("refreshToken should be null when not rotated", success!!.refreshToken)

        verify(keystoreManager).saveAccessToken("new_access_token")
        verify(keystoreManager, never()).saveRefreshToken(any())
    }

    // ── Permanent failures ─────────────────────────────────────────────────

    @Test
    fun `no refresh token returns permanent failure`() = runTest {
        whenever(keystoreManager.getRefreshToken()).thenReturn(null)

        val result = coordinator.refreshIfPossible()

        val failure = result as? TokenRefreshResult.Failure
        assertNotNull("Expected Failure", failure)
        assertTrue(
            "NoRefreshToken should be permanent",
            failure!!.error.isPermanent,
        )
        assertTrue(
            "Error should be NoRefreshToken",
            failure.error is TokenRefreshError.NoRefreshToken,
        )

        verify(authStub, never()).refreshToken(any(), any())
    }

    @Test
    fun `no device id returns permanent failure`() = runTest {
        whenever(keystoreManager.getRefreshToken()).thenReturn("some_refresh_token")
        whenever(keystoreManager.getDeviceId()).thenReturn(null)

        val result = coordinator.refreshIfPossible()

        val failure = result as? TokenRefreshResult.Failure
        assertNotNull("Expected Failure", failure)
        assertTrue(
            "NoDeviceId should be permanent",
            failure!!.error.isPermanent,
        )
        assertTrue(
            "Error should be NoDeviceId",
            failure.error is TokenRefreshError.NoDeviceId,
        )

        verify(authStub, never()).refreshToken(any(), any())
    }

    @Test
    fun `unauthenticated gRPC status returns permanent failure`() = runTest {
        whenever(keystoreManager.getRefreshToken()).thenReturn("expired_refresh")
        whenever(keystoreManager.getDeviceId()).thenReturn("device-1")
        whenever(authStub.refreshToken(any(), any()))
            .thenThrow(StatusRuntimeException(Status.UNAUTHENTICATED))

        val result = coordinator.refreshIfPossible()

        val failure = result as? TokenRefreshResult.Failure
        assertNotNull("Expected Failure", failure)
        assertTrue(
            "TokenRevoked should be permanent",
            failure!!.error.isPermanent,
        )
        assertTrue(
            "Error should be TokenRevoked",
            failure.error is TokenRefreshError.TokenRevoked,
        )

        verify(keystoreManager, never()).saveAccessToken(any())
    }

    @Test
    fun `permission denied gRPC status returns permanent failure`() = runTest {
        whenever(keystoreManager.getRefreshToken()).thenReturn("stale_refresh")
        whenever(keystoreManager.getDeviceId()).thenReturn("device-1")
        whenever(authStub.refreshToken(any(), any()))
            .thenThrow(StatusRuntimeException(Status.PERMISSION_DENIED))

        val result = coordinator.refreshIfPossible()

        val failure = result as? TokenRefreshResult.Failure
        assertNotNull("Expected Failure", failure)
        assertTrue(
            "TokenRevoked should be permanent",
            failure!!.error.isPermanent,
        )
        assertTrue(
            "Error should be TokenRevoked",
            failure.error is TokenRefreshError.TokenRevoked,
        )
    }

    // ── Transient failures ─────────────────────────────────────────────────

    @Test
    fun `network error returns transient failure`() = runTest {
        whenever(keystoreManager.getRefreshToken()).thenReturn("valid_refresh")
        whenever(keystoreManager.getDeviceId()).thenReturn("device-1")
        // gRPC reports connection errors as UNAVAILABLE, not a raw IOException.
        whenever(authStub.refreshToken(any(), any()))
            .thenThrow(StatusRuntimeException(Status.UNAVAILABLE))

        val result = coordinator.refreshIfPossible()

        val failure = result as? TokenRefreshResult.Failure
        assertNotNull("Expected Failure", failure)
        assertFalse(
            "NetworkError should NOT be permanent",
            failure!!.error.isPermanent,
        )
        assertTrue(
            "Error should be NetworkError",
            failure.error is TokenRefreshError.NetworkError,
        )
    }

    @Test
    fun `internal gRPC error returns transient failure`() = runTest {
        whenever(keystoreManager.getRefreshToken()).thenReturn("valid_refresh")
        whenever(keystoreManager.getDeviceId()).thenReturn("device-1")
        whenever(authStub.refreshToken(any(), any()))
            .thenThrow(StatusRuntimeException(Status.INTERNAL))

        val result = coordinator.refreshIfPossible()

        val failure = result as? TokenRefreshResult.Failure
        assertNotNull("Expected Failure", failure)
        assertFalse(
            "Internal gRPC error should NOT be permanent",
            failure!!.error.isPermanent,
        )
        assertTrue(
            "Error should be NetworkError",
            failure.error is TokenRefreshError.NetworkError,
        )
    }

    // ── Single-flight ──────────────────────────────────────────────────────

    @Test
    fun `concurrent callers make only one RPC`() = runTest {
        whenever(keystoreManager.getRefreshToken()).thenReturn("refresh_token")
        whenever(keystoreManager.getDeviceId()).thenReturn("device-1")
        val response = RefreshTokenResponse.newBuilder()
            .setAccessToken("access_1")
            .setExpiresAt(1_800_000_000L)
            .build()
        whenever(authStub.refreshToken(any(), any())).thenReturn(response)

        coordinator.invalidateCache()

        val deferred1 = async { coordinator.refreshIfPossible() }
        val deferred2 = async { coordinator.refreshIfPossible() }

        val result1 = deferred1.await()
        val result2 = deferred2.await()

        // Both should succeed
        assertTrue("Caller 1 should succeed", result1 is TokenRefreshResult.Success)
        assertTrue("Caller 2 should succeed", result2 is TokenRefreshResult.Success)

        // Both see the same access token
        assertEquals(
            "access_1",
            (result1 as TokenRefreshResult.Success).accessToken,
        )
        assertEquals(
            "access_1",
            (result2 as TokenRefreshResult.Success).accessToken,
        )

        // Only ONE RPC call was made — single-flight
        verify(authStub, times(1)).refreshToken(any(), any())
    }

    // ── Cache invalidation ─────────────────────────────────────────────────

    @Test
    fun `successful refresh is cached`() = runTest {
        whenever(keystoreManager.getRefreshToken()).thenReturn("refresh_token")
        whenever(keystoreManager.getDeviceId()).thenReturn("device-1")
        val response = RefreshTokenResponse.newBuilder()
            .setAccessToken("access_1")
            .setExpiresAt(1_800_000_000L)
            .build()
        whenever(authStub.refreshToken(any(), any())).thenReturn(response)

        coordinator.invalidateCache()

        // First call — makes RPC
        coordinator.refreshIfPossible()
        // Second call — should use cache, no RPC
        coordinator.refreshIfPossible()

        verify(authStub, times(1)).refreshToken(any(), any())
    }

    @Test
    fun `invalidateCache forces a fresh RPC`() = runTest {
        whenever(keystoreManager.getRefreshToken()).thenReturn("refresh_token")
        whenever(keystoreManager.getDeviceId()).thenReturn("device-1")
        val response = RefreshTokenResponse.newBuilder()
            .setAccessToken("access_1")
            .setExpiresAt(1_800_000_000L)
            .build()
        whenever(authStub.refreshToken(any(), any())).thenReturn(response)

        coordinator.invalidateCache()

        coordinator.refreshIfPossible()
        verify(authStub, times(1)).refreshToken(any(), any())

        coordinator.invalidateCache()

        coordinator.refreshIfPossible()
        verify(authStub, times(2)).refreshToken(any(), any())
    }

    // ── Refused device (TODO 126) ───────────────────────────────────────────

    /** How the coroutine stub throws on a device: `StatusException`, with the reason in the trailers. */
    private fun refused(number: String?): io.grpc.StatusException =
        io.grpc.StatusException(
            Status.UNAUTHENTICATED.withDescription("Device is inactive"),
            io.grpc.Metadata().apply {
                if (number != null) put(io.grpc.Metadata.Key.of("construct-device-refusal", io.grpc.Metadata.ASCII_STRING_MARSHALLER), number)
            },
        )

    private suspend fun refreshRefused(number: String?): TokenRefreshResult {
        whenever(keystoreManager.getRefreshToken()).thenReturn("old_refresh_token")
        whenever(keystoreManager.getDeviceId()).thenReturn("device-1")
        whenever(authStub.refreshToken(any(), any())).thenAnswer { throw refused(number) }
        return coordinator.refreshIfPossible()
    }

    /**
     * A device with a stored session never signs in again; it only refreshes. A removal is heard
     * here or not at all. Mutation: drop the reading — reddens.
     */
    @Test
    fun `a removal heard at refresh over direct TLS erases the device`() = runTest {
        val result = refreshRefused("1")
        assertEquals(TokenRefreshError.DeviceRemoved, (result as TokenRefreshResult.Failure).error)
        assertTrue(removal.removed.value)
    }

    /** Mutation: ignore the route — a relay could erase the device. */
    @Test
    fun `a removal heard through VEIL at refresh does not erase`() = runTest {
        whenever(grpcClient.veilPort).thenReturn(40_000)
        val result = refreshRefused("1")
        assertEquals(TokenRefreshError.TokenRevoked, (result as TokenRefreshResult.Failure).error)
        assertFalse(removal.removed.value)
    }

    /** NOT_FOUND answers an unapproved join request too: never an erase. */
    @Test
    fun `not found at refresh does not erase`() = runTest {
        refreshRefused("2")
        assertFalse(removal.removed.value)
    }

    /**
     * A revoked token, thrown as the stub throws it, is permanent — not a network error retried
     * forever. Mutation: catch only `StatusRuntimeException` — reddens.
     */
    @Test
    fun `a revoked token thrown as StatusException is permanent`() = runTest {
        val result = refreshRefused(null)
        assertEquals(TokenRefreshError.TokenRevoked, (result as TokenRefreshResult.Failure).error)
    }
}
