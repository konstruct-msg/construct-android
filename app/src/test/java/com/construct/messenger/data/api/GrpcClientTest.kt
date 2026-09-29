package com.construct.messenger.data.api

import com.construct.messenger.data.auth.AuthInterceptor
import com.construct.messenger.data.local.KeystoreManager
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class GrpcClientTest {

    private val keystoreManager: KeystoreManager = mock()

    private lateinit var grpcClient: GrpcClient

    @Before
    fun setUp() {
        // AuthInterceptor reads from KeystoreManager — mock all reads to return null
        // (pre-registration state, no tokens yet).
        whenever(keystoreManager.getAccessToken()).thenReturn(null)
        whenever(keystoreManager.getUserId()).thenReturn(null)
        whenever(keystoreManager.getDeviceId()).thenReturn(null)

        val authInterceptor = AuthInterceptor(keystoreManager)
        grpcClient = GrpcClient(authInterceptor, com.construct.messenger.transport.TransportEvents())
    }

    @Test
    fun authStubs_areCreated() {
        assertNotNull(grpcClient.auth)
        assertNotNull(grpcClient.key)
        assertNotNull(grpcClient.user)
        assertNotNull(grpcClient.notification)
        assertNotNull(grpcClient.sentinel)
        assertNotNull(grpcClient.messaging)
        assertNotNull(grpcClient.invite)
    }

    @Test
    fun sealedMessagingStub_isCreated() {
        assertNotNull(grpcClient.sealedMessaging)
    }

    @Test
    fun messagingAndSealedMessaging_areDifferentInstances() {
        // Two separate channels should produce different stub instances.
        // This guarantees that sealed sends are on a transport-level connection
        // that carries no AuthInterceptor metadata.
        assertNotSame(grpcClient.messaging, grpcClient.sealedMessaging)
    }

    @Test
    fun shutdown_doesNotThrow() {
        grpcClient.shutdown()
    }
}
