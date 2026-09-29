package com.construct.messenger.service

import com.construct.messenger.crypto.CryptoManager
import com.construct.messenger.data.api.GrpcClient
import com.construct.messenger.data.local.db.UserDao
import com.construct.messenger.data.local.PeerDeviceRegistry
import com.google.protobuf.ByteString
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import shared.proto.services.v1.KeyServiceGrpcKt.KeyServiceCoroutineStub
import shared.proto.services.v1.KeyServiceOuterClass.GetPreKeyBundleResponse
import shared.proto.services.v1.KeyServiceOuterClass.PreKeyBundle
import uniffi.construct_core.BinaryKeyBundle

/**
 * Covers [SessionManager.initSession]'s private `PreKeyBundle.toBinaryKeyBundle()` mapper
 * indirectly (through the public entry point) since it's the riskiest hand-written part of
 * the gRPC integration: 14 fields, several optional, no compiler help if a field is dropped.
 */
class SessionManagerTest {

    private val cryptoManager: CryptoManager = mock()
    private val grpcClient: GrpcClient = mock()
    private val userDao: UserDao = mock()
    private val peerDeviceRegistry: PeerDeviceRegistry = mock()
    private val keyStub: KeyServiceCoroutineStub = mock()

    private lateinit var sessionManager: SessionManager

    @Before
    fun setUp() {
        whenever(grpcClient.key).thenReturn(keyStub)
        sessionManager = SessionManager(cryptoManager, grpcClient, userDao, peerDeviceRegistry)
    }

    @Test
    fun initSession_mapsRequiredClassicFields_andLeavesOptionalsNull() = runTest {
        val bundle = PreKeyBundle.newBuilder()
            .setIdentityKey(ByteString.copyFrom(byteArrayOf(1, 2, 3)))
            .setSignedPreKey(ByteString.copyFrom(byteArrayOf(4, 5, 6)))
            .setSignedPreKeySignature(ByteString.copyFrom(byteArrayOf(7, 8)))
            .setCryptoSuiteValue(10) // CRYPTO_SUITE_CLASSIC_X25519_CHACHA20
            .setSpkUploadedAt(1_700_000_000L)
            .setSpkRotationEpoch(3)
            .build()
        val response = GetPreKeyBundleResponse.newBuilder()
            .setBundle(bundle)
            .setVerifyingKey(ByteString.copyFrom(byteArrayOf(9, 9)))
            .build()
        whenever(keyStub.getPreKeyBundle(org.mockito.kotlin.any(), org.mockito.kotlin.any())).thenReturn(response)
        whenever(cryptoManager.deriveDeviceIdFromIdentity(org.mockito.kotlin.any())).thenReturn("11111111111111111111111111111111")
        whenever(cryptoManager.initSession(eq("11111111111111111111111111111111"), org.mockito.kotlin.any())).thenReturn("session-1")

        val result = sessionManager.initSession("contact-1")

        assertEquals("session-1", result)

        val bundleCaptor = argumentCaptor<BinaryKeyBundle>()
        verify(cryptoManager).initSession(eq("11111111111111111111111111111111"), bundleCaptor.capture())
        val mapped = bundleCaptor.firstValue

        assertArrayEquals(byteArrayOf(1, 2, 3), mapped.identityPublic)
        assertArrayEquals(byteArrayOf(4, 5, 6), mapped.signedPrekeyPublic)
        assertArrayEquals(byteArrayOf(7, 8), mapped.signature)
        assertArrayEquals(byteArrayOf(9, 9), mapped.verifyingKey)
        // Proto CRYPTO_SUITE_CLASSIC_X25519_CHACHA20 (=10) maps to the CORE SuiteID 1
        // (CLASSIC) — the raw proto value would be rejected by SuiteID::new.
        assertEquals(1, mapped.suiteId.toInt())
        assertEquals(1_700_000_000UL, mapped.spkUploadedAt)
        assertEquals(3u, mapped.spkRotationEpoch)

        assertNull(mapped.oneTimePrekeyPublic)
        assertNull(mapped.oneTimePrekeyId)
        // An absent proto field reads as absent — never an empty value the core would verify.
        assertNull(mapped.kyberPreKeyPublic)
        assertNull(mapped.kyberPreKeyId)
        assertNull(mapped.kyberPreKeyCreatedAt)
        assertNull(mapped.kyberPreKeySignature)
        assertNull(mapped.kyberPreKeyHybridSignature)
        assertNull(mapped.kyberOneTimePrekeyPublic)
        assertNull(mapped.kyberOneTimePrekeyId)
        assertNull(mapped.kyberOneTimePrekeyCreatedAt)
        assertNull(mapped.kyberOneTimePrekeySignature)
        assertNull(mapped.kyberOneTimePrekeyHybridSignature)
        assertNull(mapped.hybridIdentityKey)
        assertNull(mapped.hybridIdentitySignature)
        assertEquals(0UL, mapped.kyberSpkUploadedAt)
        assertEquals(0u, mapped.kyberSpkRotationEpoch)
    }

    @Test
    fun initSession_mapsOptionalAndKyberFieldsWhenPresent() = runTest {
        val bundle = PreKeyBundle.newBuilder()
            .setIdentityKey(ByteString.copyFrom(byteArrayOf(1)))
            .setSignedPreKey(ByteString.copyFrom(byteArrayOf(2)))
            .setSignedPreKeySignature(ByteString.copyFrom(byteArrayOf(3)))
            .setCryptoSuiteValue(2) // CRYPTO_SUITE_HYBRID_KYBER768_X25519
            .setOneTimePreKey(ByteString.copyFrom(byteArrayOf(4)))
            .setOneTimePreKeyId(42)
            .setSpkUploadedAt(1L)
            .setSpkRotationEpoch(1)
            .setKyberPreKey(ByteString.copyFrom(byteArrayOf(5)))
            .setKyberPreKeyId(11)
            .setKyberPreKeyCreatedAt(1_800_000_000L)
            .setKyberPreKeySignature(ByteString.copyFrom(byteArrayOf(12)))
            .setKyberPreKeyHybridSignature(ByteString.copyFrom(byteArrayOf(13)))
            .setKyberOneTimePreKey(ByteString.copyFrom(byteArrayOf(6)))
            .setKyberOneTimePreKeyId(7)
            .setKyberOneTimePreKeyCreatedAt(1_800_000_060L)
            .setKyberOneTimePreKeySignature(ByteString.copyFrom(byteArrayOf(14)))
            .setKyberOneTimePreKeyHybridSignature(ByteString.copyFrom(byteArrayOf(15)))
            .setHybridIdentityKey(ByteString.copyFrom(byteArrayOf(16)))
            .setHybridIdentitySignature(ByteString.copyFrom(byteArrayOf(17)))
            .setKyberSpkUploadedAt(2_000L)
            .setKyberSpkRotationEpoch(4)
            .build()
        val response = GetPreKeyBundleResponse.newBuilder()
            .setBundle(bundle)
            .setVerifyingKey(ByteString.copyFrom(byteArrayOf(8)))
            .build()
        whenever(keyStub.getPreKeyBundle(org.mockito.kotlin.any(), org.mockito.kotlin.any())).thenReturn(response)
        whenever(cryptoManager.initSession(org.mockito.kotlin.any(), org.mockito.kotlin.any())).thenReturn("session-2")
        whenever(cryptoManager.deriveDeviceIdFromIdentity(org.mockito.kotlin.any())).thenReturn("22222222222222222222222222222222")

        sessionManager.initSession("contact-2")

        val bundleCaptor = argumentCaptor<BinaryKeyBundle>()
        verify(cryptoManager).initSession(eq("22222222222222222222222222222222"), bundleCaptor.capture())
        val mapped = bundleCaptor.firstValue

        assertArrayEquals(byteArrayOf(4), mapped.oneTimePrekeyPublic)
        assertEquals(42u, mapped.oneTimePrekeyId)
        // Every field the PQXDH v2 initiator checks survives the conversion: a dropped one would
        // read as "peer not post-quantum" for every peer.
        assertArrayEquals(byteArrayOf(5), mapped.kyberPreKeyPublic)
        assertEquals(11u, mapped.kyberPreKeyId)
        assertEquals(1_800_000_000UL, mapped.kyberPreKeyCreatedAt)
        assertArrayEquals(byteArrayOf(12), mapped.kyberPreKeySignature)
        assertArrayEquals(byteArrayOf(13), mapped.kyberPreKeyHybridSignature)
        assertArrayEquals(byteArrayOf(6), mapped.kyberOneTimePrekeyPublic)
        assertEquals(7u, mapped.kyberOneTimePrekeyId)
        assertEquals(1_800_000_060UL, mapped.kyberOneTimePrekeyCreatedAt)
        assertArrayEquals(byteArrayOf(14), mapped.kyberOneTimePrekeySignature)
        assertArrayEquals(byteArrayOf(15), mapped.kyberOneTimePrekeyHybridSignature)
        assertArrayEquals(byteArrayOf(16), mapped.hybridIdentityKey)
        assertArrayEquals(byteArrayOf(17), mapped.hybridIdentitySignature)
        assertEquals(2_000UL, mapped.kyberSpkUploadedAt)
        assertEquals(4u, mapped.kyberSpkRotationEpoch)
        // Proto CRYPTO_SUITE_HYBRID_KYBER768_X25519 (=2) → core SuiteID 2 (PQ_HYBRID).
        // NEVER 3: PQ_RATCHET is the core's choice for every session, not declared by a bundle.
        assertEquals(2, mapped.suiteId.toInt())
    }
}
