package com.construct.messenger.domain.usecase

import com.construct.messenger.crypto.CryptoManager
import com.construct.messenger.data.api.GrpcClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals

import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import shared.proto.services.v1.KeyServiceGrpcKt.KeyServiceCoroutineStub
import shared.proto.services.v1.KeyServiceOuterClass.GetPreKeyCountRequest
import shared.proto.services.v1.KeyServiceOuterClass.GetPreKeyCountResponse
import shared.proto.services.v1.KeyServiceOuterClass.OneTimePreKey
import shared.proto.services.v1.KeyServiceOuterClass.UploadPreKeysRequest
import shared.proto.services.v1.KeyServiceOuterClass.UploadPreKeysResponse
import uniffi.construct_core.OtpkPair

class UploadPreKeysUseCaseTest {

    private val cryptoManager: CryptoManager = mock()
    private val grpcClient: GrpcClient = mock()
    private val keyStub: KeyServiceCoroutineStub = mock()

    private lateinit var useCase: UploadPreKeysUseCase

    @Before
    fun setUp() {
        whenever(grpcClient.key).thenReturn(keyStub)
        useCase = UploadPreKeysUseCase(cryptoManager, grpcClient)
    }

    // ── invoke (direct upload) ─────────────────────────────────────────────

    @Test
    fun invoke_generatesAndUploadsOtPKs() = runTest {
        whenever(cryptoManager.generateOneTimePrekeys(any())).thenReturn(
            listOf(OtpkPair(keyId = 1u, publicKey = listOf(10u, 20u))),
        )
        whenever(cryptoManager.supportsPqRatchet()).thenReturn(true)
        whenever(keyStub.uploadPreKeys(any(), any())).thenReturn(
            UploadPreKeysResponse.newBuilder().setSuccess(true).build(),
        )

        val result = useCase("device-1", count = 50, replaceExisting = true)

        assertEquals(UploadPreKeysResult.Uploaded(50), result)

        val captor = argumentCaptor<UploadPreKeysRequest>()
        verify(keyStub).uploadPreKeys(captor.capture(), any())
        val request = captor.firstValue
        assertEquals("device-1", request.deviceId)
        assertEquals(1, request.preKeysCount)
        assertEquals(1u, request.preKeysList.first().keyId.toUInt())
        assertTrue(request.replaceExisting)
        assertTrue(request.supportsPqRatchet)
    }

    @Test
    fun invoke_usesDefaultBatchSize() = runTest {
        whenever(cryptoManager.generateOneTimePrekeys(eq(100))).thenReturn(
            (1..100).map { i -> OtpkPair(keyId = i.toUInt(), publicKey = listOf(i.toUByte())) },
        )
        whenever(cryptoManager.supportsPqRatchet()).thenReturn(false)
        whenever(keyStub.uploadPreKeys(any(), any())).thenReturn(
            UploadPreKeysResponse.newBuilder().setSuccess(true).build(),
        )

        val result = useCase("device-2")

        assertEquals(UploadPreKeysResult.Uploaded(100), result)
        val captor = argumentCaptor<UploadPreKeysRequest>()
        verify(keyStub).uploadPreKeys(captor.capture(), any())
        assertEquals(100, captor.firstValue.preKeysCount)
    }

    @Test
    fun invoke_returnsFailed_onGrpcError() = runTest {
        whenever(cryptoManager.generateOneTimePrekeys(any())).thenReturn(
            listOf(OtpkPair(keyId = 1u, publicKey = listOf(1u))),
        )
        whenever(keyStub.uploadPreKeys(any(), any())).thenThrow(RuntimeException("unavailable"))

        val result = useCase("device-1")

        assertTrue(result is UploadPreKeysResult.Failed)
        val failed = result as UploadPreKeysResult.Failed
        assertEquals("unavailable", failed.error.message)
    }

    @Test
    fun invoke_returnsFailed_onCryptoError() = runTest {
        whenever(cryptoManager.generateOneTimePrekeys(any())).thenThrow(
            RuntimeException("crypto error"),
        )

        val result = useCase("device-1")

        assertTrue(result is UploadPreKeysResult.Failed)
    }

    // ── replenishIfNeeded ─────────────────────────────────────────────────

    @Test
    fun replenishIfNeeded_skipsUpload_whenCountIsSufficient() = runTest {
        whenever(keyStub.getPreKeyCount(any(), any())).thenReturn(
            GetPreKeyCountResponse.newBuilder().setCount(50).build(),
        )

        val result = useCase.replenishIfNeeded("device-1", minThreshold = 20)

        assertEquals(UploadPreKeysResult.Skipped, result)
        verify(keyStub, never()).uploadPreKeys(any(), any())
    }

    @Test
    fun replenishIfNeeded_uploadsKeys_whenCountIsLow() = runTest {
        whenever(keyStub.getPreKeyCount(any(), any())).thenReturn(
            GetPreKeyCountResponse.newBuilder().setCount(5).build(),
        )
        whenever(cryptoManager.generateOneTimePrekeys(any())).thenReturn(
            listOf(OtpkPair(keyId = 1u, publicKey = listOf(1u))),
        )
        whenever(keyStub.uploadPreKeys(any(), any())).thenReturn(
            UploadPreKeysResponse.newBuilder().setSuccess(true).build(),
        )

        val result = useCase.replenishIfNeeded("device-1", minThreshold = 20)

        assertEquals(UploadPreKeysResult.Uploaded(100), result)
        verify(keyStub).uploadPreKeys(any(), any())
    }

    @Test
    fun replenishIfNeeded_returnsFailed_whenCountCheckFails() = runTest {
        whenever(keyStub.getPreKeyCount(any(), any())).thenThrow(RuntimeException("network"))

        val result = useCase.replenishIfNeeded("device-1")

        assertTrue(result is UploadPreKeysResult.Failed)
    }

    @Test
    fun replenishIfNeeded_returnsFailed_whenUploadAfterCheckFails() = runTest {
        whenever(keyStub.getPreKeyCount(any(), any())).thenReturn(
            GetPreKeyCountResponse.newBuilder().setCount(3).build(),
        )
        whenever(cryptoManager.generateOneTimePrekeys(any())).thenThrow(
            RuntimeException("crypto error"),
        )

        val result = useCase.replenishIfNeeded("device-1")

        assertTrue(result is UploadPreKeysResult.Failed)
    }
}
