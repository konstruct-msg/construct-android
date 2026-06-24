package com.construct.messenger.domain.usecase

import com.construct.messenger.crypto.CryptoManager
import com.construct.messenger.data.api.GrpcClient
import com.construct.messenger.data.local.KeystoreManager
import com.google.protobuf.ByteString
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import shared.proto.services.v1.AuthServiceGrpcKt.AuthServiceCoroutineStub
import shared.proto.services.v1.AuthServiceOuterClass.AuthTokensResponse
import shared.proto.services.v1.AuthServiceOuterClass.AuthenticateDeviceRequest
import shared.proto.services.v1.AuthServiceOuterClass.AuthenticateDeviceResponse

class LoginUseCaseTest {

    private val cryptoManager: CryptoManager = mock()
    private val grpcClient: GrpcClient = mock()
    private val keystoreManager: KeystoreManager = mock()
    private val authStub: AuthServiceCoroutineStub = mock()

    private lateinit var loginUseCase: LoginUseCase

    @Before
    fun setUp() {
        whenever(grpcClient.auth).thenReturn(authStub)
        loginUseCase = LoginUseCase(cryptoManager, grpcClient, keystoreManager)
    }

    @Test
    fun invoke_signsDeviceIdAndTimestamp_andPersistsTokens() = runTest {
        val signature = byteArrayOf(1, 2, 3)
        whenever(cryptoManager.signWithDeviceKey(any())).thenReturn(signature)

        val tokens = AuthTokensResponse.newBuilder()
            .setUserId("user-1")
            .setAccessToken("access-1")
            .setRefreshToken("refresh-1")
            .build()
        whenever(authStub.authenticateDevice(any(), any()))
            .thenReturn(AuthenticateDeviceResponse.newBuilder().setTokens(tokens).build())

        val savedKeys = byteArrayOf(9, 9, 9)
        val result = loginUseCase("device-1", savedKeys)

        assertEquals(tokens, result)

        verify(cryptoManager).loadOrCreate(savedKeys)

        val signedMessageCaptor = argumentCaptor<String>()
        verify(cryptoManager).signWithDeviceKey(signedMessageCaptor.capture())
        assertEquals(true, signedMessageCaptor.firstValue.startsWith("device-1"))

        val requestCaptor = argumentCaptor<AuthenticateDeviceRequest>()
        verify(authStub).authenticateDevice(requestCaptor.capture(), any())
        val request = requestCaptor.firstValue
        assertEquals("device-1", request.deviceId)
        assertEquals(ByteString.copyFrom(signature), request.signature)
        assertEquals("device-1${request.timestamp}", signedMessageCaptor.firstValue)

        verify(cryptoManager).setLocalUserId("user-1")
        verify(keystoreManager).saveTokens(tokens, "device-1")
    }
}
