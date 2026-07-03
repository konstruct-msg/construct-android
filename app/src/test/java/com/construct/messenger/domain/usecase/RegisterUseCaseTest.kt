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
import shared.proto.services.v1.KeyServiceGrpcKt.KeyServiceCoroutineStub

import uniffi.construct_core.OtpkPair
import uniffi.construct_core.PowSolution
import uniffi.construct_core.RegistrationBundleFields

class RegisterUseCaseTest {

    private val cryptoManager: CryptoManager = mock()
    private val grpcClient: GrpcClient = mock()
    private val keystoreManager: KeystoreManager = mock()
    private val authStub: AuthServiceCoroutineStub = mock()
    private val keyStub: KeyServiceCoroutineStub = mock()

    private val uploadPreKeysUseCase: UploadPreKeysUseCase = mock()
    private lateinit var registerUseCase: RegisterUseCase

    @Before
    fun setUp() {
        whenever(grpcClient.auth).thenReturn(authStub)
        whenever(grpcClient.key).thenReturn(keyStub)
        whenever(cryptoManager.generateOneTimePrekeys(any())).thenReturn(
            listOf(OtpkPair(keyId = 1u, publicKey = listOf(1u, 2u))),
        )
        registerUseCase = RegisterUseCase(cryptoManager, grpcClient, keystoreManager, uploadPreKeysUseCase)
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
        whenever(cryptoManager.deriveDeviceId(bundle)).thenReturn("device-1")

        val challengeResponse = GetPowChallengeResponse.newBuilder()
            .setChallenge("chal-1")
            .setDifficulty(12)
            .setExpiresAt(1_700_000_500L)
            .build()
        whenever(authStub.getPowChallenge(any(), any())).thenReturn(challengeResponse)

        whenever(cryptoManager.computePow(eq("chal-1"), eq(12), any()))
            .thenReturn(PowSolution(nonce = 99uL, hash = "hash-1"))

        val tokens = AuthTokensResponse.newBuilder()
            .setUserId("user-1")
            .setAccessToken("access-1")
            .setRefreshToken("refresh-1")
            .build()
        whenever(authStub.registerDevice(any(), any()))
            .thenReturn(RegisterDeviceResponse.newBuilder().setTokens(tokens).build())


        val steps = mutableListOf<RegistrationStep>()
        val result = registerUseCase("alice") { steps += it }

        assertEquals(tokens, result.tokens)
        assertEquals("device-1", result.deviceId)
        assertEquals(
            listOf(
                RegistrationStep.GeneratingKeys,
                RegistrationStep.FetchingChallenge,
                RegistrationStep.ComputingPow(0f),
                RegistrationStep.SubmittingRegistration,
                RegistrationStep.Complete,
            ),
            steps,
        )

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

        verify(uploadPreKeysUseCase).invoke(
            eq("device-1"),
            eq(100),
            eq(true),
        )
    }

    @Test
    fun invoke_otpkUploadFailure_doesNotFailRegistration() = runTest {
        val bundle = RegistrationBundleFields(
            identityPublic = listOf(1u),
            signedPrekeyPublic = listOf(2u),
            signature = listOf(3u),
            verifyingKey = listOf(4u),
            suiteId = 10u,
        )
        whenever(cryptoManager.loadOrCreate()).thenReturn(bundle)
        whenever(cryptoManager.deriveDeviceId(bundle)).thenReturn("device-3")
        whenever(authStub.getPowChallenge(any(), any())).thenReturn(
            GetPowChallengeResponse.newBuilder().setChallenge("c").setDifficulty(1).build(),
        )
        whenever(cryptoManager.computePow(any(), any(), any())).thenReturn(PowSolution(1uL, "h"))
        whenever(authStub.registerDevice(any(), any())).thenReturn(
            RegisterDeviceResponse.newBuilder()
                .setTokens(AuthTokensResponse.newBuilder().setUserId("u").build())
                .build(),
        )
        whenever(uploadPreKeysUseCase.invoke(any(), any(), any()))
            .thenReturn(UploadPreKeysResult.Failed(RuntimeException("network down")))

        val result = registerUseCase("alice")

        assertEquals("device-3", result.deviceId)
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
        whenever(cryptoManager.deriveDeviceId(bundle)).thenReturn("device-2")
        whenever(authStub.getPowChallenge(any(), any())).thenReturn(
            GetPowChallengeResponse.newBuilder().setChallenge("c").setDifficulty(1).build(),
        )
        whenever(cryptoManager.computePow(any(), any(), any())).thenReturn(PowSolution(1uL, "h"))
        whenever(authStub.registerDevice(any(), any())).thenReturn(
            RegisterDeviceResponse.newBuilder()
                .setTokens(AuthTokensResponse.newBuilder().setUserId("u").build())
                .build(),
        )
        whenever(uploadPreKeysUseCase.invoke(any(), any(), any()))
            .thenReturn(UploadPreKeysResult.Uploaded(1))

        registerUseCase(null)

        val requestCaptor = argumentCaptor<RegisterDeviceRequest>()
        verify(authStub).registerDevice(requestCaptor.capture(), any())
        assertEquals("", requestCaptor.firstValue.username)
    }
}

