package com.construct.messenger.domain.usecase

import com.construct.messenger.crypto.CryptoManager
import com.construct.messenger.data.api.GrpcClient
import com.construct.messenger.data.local.KeystoreManager
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import shared.proto.services.v1.AuthServiceGrpcKt.AuthServiceCoroutineStub
import shared.proto.services.v1.AuthServiceOuterClass.AuthTokensResponse
import shared.proto.services.v1.AuthServiceOuterClass.GetPowChallengeResponse
import shared.proto.services.v1.AuthServiceOuterClass.RegisterDeviceRequest
import shared.proto.services.v1.AuthServiceOuterClass.RegisterDeviceResponse
import uniffi.construct_core.PowSolution
import uniffi.construct_core.RegistrationBundleFields

class RegisterUseCaseTest {

    private val cryptoManager: CryptoManager = mock()
    private val grpcClient: GrpcClient = mock()
    private val keystoreManager: KeystoreManager = mock()
    private val authStub: AuthServiceCoroutineStub = mock()

    private lateinit var registerUseCase: RegisterUseCase

    @Before
    fun setUp() {
        whenever(grpcClient.auth).thenReturn(authStub)
        registerUseCase = RegisterUseCase(cryptoManager, grpcClient, keystoreManager)
    }

    @Test
    fun invoke_buildsRequestFromBundleAndPowSolution_andPersistsTokens() = runTest {
        val bundle = RegistrationBundleFields(
            identityPublic = listOf(1u, 2u),
            signedPrekeyPublic = listOf(3u, 4u),
            signature = listOf(5u, 6u),
            verifyingKey = listOf(7u, 8u),
            suiteId = 10u,
        )
        whenever(cryptoManager.loadOrCreate()).thenReturn(bundle)

        val challengeResponse = GetPowChallengeResponse.newBuilder()
            .setChallenge("chal-1")
            .setDifficulty(12)
            .setExpiresAt(1_700_000_500L)
            .build()
        whenever(authStub.getPowChallenge(any(), any())).thenReturn(challengeResponse)

        whenever(cryptoManager.computePow(eq("chal-1"), eq(12)))
            .thenReturn(PowSolution(nonce = 99uL, hash = "hash-1"))

        val tokens = AuthTokensResponse.newBuilder()
            .setUserId("user-1")
            .setAccessToken("access-1")
            .setRefreshToken("refresh-1")
            .build()
        whenever(authStub.registerDevice(any(), any()))
            .thenReturn(RegisterDeviceResponse.newBuilder().setTokens(tokens).build())

        val result = registerUseCase("alice", "device-1")

        assertEquals(tokens, result)

        val requestCaptor = argumentCaptor<RegisterDeviceRequest>()
        verify(authStub).registerDevice(requestCaptor.capture(), any())
        val request = requestCaptor.firstValue

        assertEquals("device-1", request.deviceId)
        assertEquals("alice", request.username)
        assertEquals("chal-1", request.powSolution.challenge)
        assertEquals(99L, request.powSolution.nonce)
        assertEquals("hash-1", request.powSolution.hash)
        assertEquals("Curve25519+Ed25519", request.publicKeys.cryptoSuite)
        assertEquals(listOf<Byte>(1, 2), request.publicKeys.identityPublic.toByteArray().toList())
        assertEquals(listOf<Byte>(3, 4), request.publicKeys.signedPrekeyPublic.toByteArray().toList())
        assertEquals(listOf<Byte>(5, 6), request.publicKeys.signedPrekeySignature.toByteArray().toList())
        assertEquals(listOf<Byte>(7, 8), request.publicKeys.verifyingKey.toByteArray().toList())

        verify(cryptoManager).setLocalUserId("user-1")
        verify(keystoreManager).saveTokens(tokens, "device-1")
    }

    @Test
    fun invoke_omitsUsername_whenNullOrBlank() = runTest {
        val bundle = RegistrationBundleFields(
            identityPublic = listOf(1u),
            signedPrekeyPublic = listOf(2u),
            signature = listOf(3u),
            verifyingKey = listOf(4u),
            suiteId = 10u,
        )
        whenever(cryptoManager.loadOrCreate()).thenReturn(bundle)
        whenever(authStub.getPowChallenge(any(), any())).thenReturn(
            GetPowChallengeResponse.newBuilder().setChallenge("c").setDifficulty(1).build(),
        )
        whenever(cryptoManager.computePow(any(), any())).thenReturn(PowSolution(1uL, "h"))
        whenever(authStub.registerDevice(any(), any())).thenReturn(
            RegisterDeviceResponse.newBuilder()
                .setTokens(AuthTokensResponse.newBuilder().setUserId("u").build())
                .build(),
        )

        registerUseCase(null, "device-2")

        val requestCaptor = argumentCaptor<RegisterDeviceRequest>()
        verify(authStub).registerDevice(requestCaptor.capture(), any())
        assertEquals("", requestCaptor.firstValue.username)
    }
}
