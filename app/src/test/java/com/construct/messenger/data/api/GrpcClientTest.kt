package com.construct.messenger.data.api

import com.construct.messenger.data.auth.AuthInterceptor
import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.transport.DirectPathGuard
import com.construct.messenger.transport.TransportEvents
import com.construct.messenger.veil.VeilMode
import com.construct.messenger.veil.VeilModeStore
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import shared.proto.services.v1.AuthServiceOuterClass.GetPowChallengeRequest
import shared.proto.services.v1.KeyServiceOuterClass.GetPreKeyBundleRequest

class GrpcClientTest {

    private val keystoreManager: KeystoreManager = mock()

    private val veilMode = MutableStateFlow(VeilMode.AUTO)
    private val veilModes: VeilModeStore = mock { on { state } doReturn veilMode }

    private lateinit var grpcClient: GrpcClient

    @Before
    fun setUp() {
        // AuthInterceptor reads from KeystoreManager — mock all reads to return null
        // (pre-registration state, no tokens yet).
        whenever(keystoreManager.getAccessToken()).thenReturn(null)
        whenever(keystoreManager.getUserId()).thenReturn(null)
        whenever(keystoreManager.getDeviceId()).thenReturn(null)

        val authInterceptor = AuthInterceptor(keystoreManager)
        grpcClient = GrpcClient(authInterceptor, TransportEvents(), veilModes)
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

    /**
     * VEIL On with no tunnel: both channels refuse before dialling (vault
     * `decisions/veil-on-never-goes-direct.md`). Mutation: drop the guard from `build` — the call
     * goes to the network and fails with another status, or not at all.
     */
    @Test
    fun veilOn_directChannelsRefuseWithoutDialling() = runBlocking {
        veilMode.value = VeilMode.ON
        val signed = runCatching { grpcClient.auth.getPowChallenge(GetPowChallengeRequest.getDefaultInstance()) }
        val sealed = runCatching { grpcClient.sealedKey.getPreKeyBundle(GetPreKeyBundleRequest.getDefaultInstance()) }
        for (outcome in listOf(signed, sealed)) {
            val status = (outcome.exceptionOrNull() as StatusException).status
            assertEquals(Status.Code.UNAVAILABLE, status.code)
            assertEquals(DirectPathGuard.STATUS.description, status.description)
        }
    }
}
