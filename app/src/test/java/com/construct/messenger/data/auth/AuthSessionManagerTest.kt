package com.construct.messenger.data.auth

import com.construct.messenger.data.local.KeystoreManager
import io.grpc.Status
import io.grpc.StatusRuntimeException
import java.util.Base64
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class AuthSessionManagerTest {

    private val keystoreManager: KeystoreManager = mock()
    private val tokenRefreshCoordinator: TokenRefreshCoordinator = mock()

    private lateinit var manager: AuthSessionManager

    @Before
    fun setUp() {
        manager = AuthSessionManager(keystoreManager, tokenRefreshCoordinator)
    }

    // ── loadSession (format guard, §13.5) ────────────────────────────────────

    @Test
    fun `loadSession returns false when no token is cached`() {
        whenever(keystoreManager.getAccessToken()).thenReturn(null)

        assertFalse(manager.loadSession())

        assertEquals(AuthSessionManager.AuthSessionState.UNKNOWN, manager.state.value)
        verify(keystoreManager, never()).clearTokens()
    }

    @Test
    fun `loadSession clears session on non-Paseto token`() {
        // A legacy JWT-shaped string must be treated as corruption (§13.5).
        whenever(keystoreManager.getAccessToken()).thenReturn("eyJhbGciOiJSUzI1NiJ9.payload.sig")

        assertFalse(manager.loadSession())

        verify(keystoreManager).clearTokens()
        assertEquals(AuthSessionManager.AuthSessionState.INVALIDATED, manager.state.value)
    }

    @Test
    fun `loadSession accepts a valid Paseto token`() {
        whenever(keystoreManager.getAccessToken()).thenReturn(buildPasetoToken(USER_ID))
        whenever(keystoreManager.getUserId()).thenReturn(USER_ID)
        whenever(keystoreManager.getDeviceId()).thenReturn(DEVICE_ID)

        assertTrue(manager.loadSession())

        assertEquals(AuthSessionManager.AuthSessionState.AUTHENTICATED, manager.state.value)
        assertEquals(USER_ID, manager.userId)
        assertEquals(DEVICE_ID, manager.deviceId)
    }

    @Test
    fun `loadSession recovers userId from sub claim when missing`() {
        whenever(keystoreManager.getAccessToken()).thenReturn(buildPasetoToken(USER_ID))
        whenever(keystoreManager.getUserId()).thenReturn(null)

        assertTrue(manager.loadSession())

        assertEquals(USER_ID, manager.userId)
        verify(keystoreManager).saveUserId(USER_ID)
    }

    // ── withAuthRetry ────────────────────────────────────────────────────────

    @Test
    fun `withAuthRetry returns on first success without touching refresh`() = runTest {
        val result = manager.withAuthRetry { "ok" }

        assertEquals("ok", result)
        verify(tokenRefreshCoordinator, never()).refreshIfPossible()
    }

    @Test
    fun `withAuthRetry retries once after a successful refresh`() = runTest {
        whenever(tokenRefreshCoordinator.refreshIfPossible()).thenReturn(
            TokenRefreshResult.Success(
                accessToken = "new_access",
                refreshToken = null,
                expiresAt = 1_800_000_000L,
            ),
        )

        var calls = 0
        val result = manager.withAuthRetry {
            calls++
            if (calls == 1) throw StatusRuntimeException(Status.UNAUTHENTICATED)
            "retried-ok"
        }

        assertEquals("retried-ok", result)
        assertEquals("call must be retried exactly once", 2, calls)
    }

    @Test
    fun `withAuthRetry invalidates session on permanent refresh failure`() = runTest {
        whenever(tokenRefreshCoordinator.refreshIfPossible()).thenReturn(
            TokenRefreshResult.Failure(TokenRefreshError.NoRefreshToken),
        )

        var thrown: SessionInvalidatedException? = null
        try {
            manager.withAuthRetry { throw StatusRuntimeException(Status.UNAUTHENTICATED) }
        } catch (e: SessionInvalidatedException) {
            thrown = e
        }

        assertNotNull("expected SessionInvalidatedException", thrown)
        assertTrue(thrown!!.reason is TokenRefreshError.NoRefreshToken)
        verify(keystoreManager).clearTokens()
        assertEquals(AuthSessionManager.AuthSessionState.INVALIDATED, manager.state.value)
    }

    @Test
    fun `withAuthRetry rethrows original error on transient refresh failure`() = runTest {
        whenever(tokenRefreshCoordinator.refreshIfPossible()).thenReturn(
            TokenRefreshResult.Failure(TokenRefreshError.NetworkError(RuntimeException("io"))),
        )

        val original = StatusRuntimeException(Status.UNAUTHENTICATED)
        var thrown: StatusRuntimeException? = null
        try {
            manager.withAuthRetry<Unit> { throw original }
        } catch (e: StatusRuntimeException) {
            thrown = e
        }

        assertTrue("original error must propagate", thrown === original)
        verify(keystoreManager, never()).clearTokens()
    }

    @Test
    fun `withAuthRetry rethrows non-auth grpc errors without refresh`() = runTest {
        try {
            manager.withAuthRetry<Unit> { throw StatusRuntimeException(Status.INTERNAL) }
            fail("expected StatusRuntimeException")
        } catch (_: StatusRuntimeException) {
            // expected
        }

        verify(tokenRefreshCoordinator, never()).refreshIfPossible()
    }

    // ── onAuthenticated / clearSession ───────────────────────────────────────

    @Test
    fun `onAuthenticated exposes cached ids and state`() {
        manager.onAuthenticated(USER_ID, DEVICE_ID)

        assertEquals(USER_ID, manager.userId)
        assertEquals(DEVICE_ID, manager.deviceId)
        assertEquals(AuthSessionManager.AuthSessionState.AUTHENTICATED, manager.state.value)
    }

    @Test
    fun `clearSession wipes tokens and drops cached userId`() {
        manager.onAuthenticated(USER_ID, DEVICE_ID)

        manager.clearSession()

        verify(keystoreManager).clearTokens()
        assertNull(manager.userId)
        assertEquals(AuthSessionManager.AuthSessionState.INVALIDATED, manager.state.value)
    }

    // ── ensureFresh (nothing refreshed a token before 2026-09-29) ───────────

    @Test
    fun `the refresh rule - five minutes early, and whenever the expiry is unknown`() {
        val now = 1_000_000L
        assertFalse(AuthSessionManager.needsRefresh(now + 3600, now))
        assertTrue(AuthSessionManager.needsRefresh(now + 299, now))
        assertTrue(AuthSessionManager.needsRefresh(now - 10, now))
        assertTrue(AuthSessionManager.needsRefresh(null, now))
    }

    /** The device-log case: a token that expired overnight. Mutation: return true early. */
    @Test
    fun `an expired token is refreshed before anything uses it`() = runTest {
        whenever(keystoreManager.getAccessToken()).thenReturn("v4.public.x")
        whenever(keystoreManager.getAccessTokenExpiresAt()).thenReturn(900L)
        whenever(tokenRefreshCoordinator.refreshIfPossible())
            .thenReturn(TokenRefreshResult.Success("new", null, 99_999L))

        assertTrue(manager.ensureFresh(nowSeconds = 1_000L))
        verify(tokenRefreshCoordinator).refreshIfPossible()
    }

    @Test
    fun `a fresh token is left alone`() = runTest {
        whenever(keystoreManager.getAccessToken()).thenReturn("v4.public.x")
        whenever(keystoreManager.getAccessTokenExpiresAt()).thenReturn(10_000L)

        assertTrue(manager.ensureFresh(nowSeconds = 1_000L))
        verify(tokenRefreshCoordinator, never()).refreshIfPossible()
    }

    /** A background refresh that fails must not wipe the session: that is device re-auth's call. */
    @Test
    fun `a failed refresh reports false and clears nothing`() = runTest {
        whenever(keystoreManager.getAccessToken()).thenReturn("v4.public.x")
        whenever(keystoreManager.getAccessTokenExpiresAt()).thenReturn(null)
        whenever(tokenRefreshCoordinator.refreshIfPossible())
            .thenReturn(TokenRefreshResult.Failure(TokenRefreshError.TokenRevoked))

        assertFalse(manager.ensureFresh(nowSeconds = 1_000L))
        verify(keystoreManager, never()).clearTokens()
    }

    @Test
    fun `the wait is until five minutes before expiry, never shorter than the retry`() {
        assertEquals(3600L - 300L, AuthSessionManager.secondsUntilRefresh(10_000L + 3600L, 10_000L))
        assertEquals(AuthSessionManager.RETRY_SECONDS, AuthSessionManager.secondsUntilRefresh(10_010L, 10_000L))
    }

    private companion object {
        const val USER_ID = "3f6f1c44-2c3a-4f5e-9c7d-1a2b3c4d5e6f"
        const val DEVICE_ID = "0123456789abcdef0123456789abcdef"

        /** Builds a structurally valid `v4.public` token: nonce(32) || JSON claims || signature(64). */
        fun buildPasetoToken(sub: String): String {
            val nonce = ByteArray(32) { 0x01 }
            val message = """{"sub":"$sub","jti":"j","exp":1,"iat":1,"iss":"construct-server"}"""
                .toByteArray(Charsets.UTF_8)
            val signature = ByteArray(64) { 0x02 }
            val payload = nonce + message + signature
            return "v4.public." + Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
        }
    }
}
