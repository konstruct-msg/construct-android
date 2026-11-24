package com.construct.messenger.devicelink

import com.construct.messenger.crypto.CryptoManager
import com.construct.messenger.security.CoreTestDevice
import com.construct.messenger.security.PeerDeviceVetting
import com.construct.messenger.service.SessionManager.PeerBundle
import com.construct.messenger.data.api.GrpcClient
import com.construct.messenger.data.api.StreamCursorStore
import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.domain.usecase.JoinSignInRefused
import com.construct.messenger.domain.usecase.LinkDeviceUseCase
import com.construct.messenger.domain.usecase.UploadPreKeysUseCase
import io.grpc.Metadata
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.reset
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import shared.proto.services.v1.AuthServiceGrpcKt.AuthServiceCoroutineStub
import shared.proto.services.v1.AuthServiceOuterClass.AuthTokensResponse
import shared.proto.services.v1.AuthServiceOuterClass.AuthenticateDeviceRequest
import shared.proto.services.v1.AuthServiceOuterClass.AuthenticateDeviceResponse
import shared.proto.services.v1.AuthServiceOuterClass.CheckJoinRequestStatusResponse
import shared.proto.services.v1.AuthServiceOuterClass.CheckJoinRequestStatusResponse.Status.STATUS_APPROVED
import shared.proto.services.v1.AuthServiceOuterClass.CheckJoinRequestStatusResponse.Status.STATUS_EXPIRED
import shared.proto.services.v1.AuthServiceOuterClass.CheckJoinRequestStatusResponse.Status.STATUS_PENDING
import shared.proto.services.v1.AuthServiceOuterClass.ConfirmDeviceLinkRequest
import shared.proto.services.v1.AuthServiceOuterClass.ConfirmDeviceLinkResponse
import shared.proto.services.v1.AuthServiceOuterClass.JoinRequestAck
import shared.proto.services.v1.AuthServiceOuterClass.JoinRequestPayload
import shared.proto.services.v1.DeviceLinkServiceGrpcKt.DeviceLinkServiceCoroutineStub
import uniffi.construct_core.BinaryKeyBundle
import uniffi.construct_core.DeviceLinkEvidence
import uniffi.construct_core.PeerDeviceVerdict
import uniffi.construct_core.newDeviceLinkSecret

/**
 * The new device's half of a link, through the real core (`decisions/devices-of-an-account-sign-each-other.md`).
 * What can go wrong here is a hybrid key that the QR names and the device later does not have — made
 * after the QR, or lost on the way into the account's orchestrator, or not saved — and the account
 * device then vouches for a key nobody can prove. **Canon:** iOS `DeviceCertificateLinkTests`.
 */
class LinkIdentityTest {
    private val old = CoreTestDevice.create()
    private val peer = CoreTestDevice.create()

    /** The new device's bundle as it publishes it once it has an account. */
    private fun published(crypto: CryptoManager): PeerBundle {
        val hybrid = crypto.ensureHybridIdentityPublicKey()
        val fields = crypto.exportPrivateKeys().let { uniffi.construct_core.createCryptoCoreFromKeys(it).getRegistrationBundleFields() }
        val bundle = BinaryKeyBundle(
            identityPublic = fields.identityPublic, signedPrekeyPublic = fields.signedPrekeyPublic, signature = fields.signature,
            verifyingKey = fields.verifyingKey, suiteId = fields.suiteId, oneTimePrekeyPublic = null, oneTimePrekeyId = null,
            spkUploadedAt = 0uL, spkRotationEpoch = 0u, kyberSpkUploadedAt = 0uL, kyberSpkRotationEpoch = 0u,
            kyberPreKeyPublic = null, kyberOneTimePrekeyPublic = null, kyberOneTimePrekeyId = null,
            hybridIdentityKey = hybrid, hybridIdentitySignature = crypto.signHybridIdentityBinding(hybrid),
        )
        return PeerBundle(CoreTestDevice.ACCOUNT, crypto.currentDeviceId()!!, fields.identityPublic, bundle = bundle)
    }

    private fun verdictOf(subject: PeerBundle, evidence: DeviceLinkEvidence): PeerDeviceVerdict {
        val certificate = old.core.issueDeviceCertificate(PeerDeviceVetting.subjectKeys(subject)!!, evidence)
        val offered = PeerDeviceVetting.offered(listOf(old.served(), subject.copy(deviceCertificate = certificate)))
        return peer.core.judgePeerDevices(listOf(old.id), offered)[1]
    }

    /**
     * Flow A: the proof made before the link covers the keys the device publishes after it.
     * Mutation: skip reloading the bootstrap core in `prepareLinkIdentity` — the account's
     * orchestrator makes another hybrid key and the account device refuses (`EvidenceMismatch`).
     */
    @Test
    fun theKeysProvedBeforeTheLinkAreTheOnesPublishedAfter() {
        val secret = newDeviceLinkSecret()
        val crypto = CryptoManager().apply { loadOrCreate() }
        val identity = crypto.prepareLinkIdentity(secret)
        crypto.setLocalUserId(CoreTestDevice.ACCOUNT)

        val subject = published(crypto)
        assertArrayEquals(identity.hybridPublic, subject.bundle.hybridIdentityKey)
        assertEquals(PeerDeviceVerdict.Vouched(old.id), verdictOf(subject, DeviceLinkEvidence.QrSecret(secret, identity.keyProof!!)))
    }

    /** Flow B: the fingerprint in our QR names the published keys; and the keys saved before the QR come back after a restart. */
    @Test
    fun theQrFingerprintNamesThePublishedKeysEvenAfterARestart() {
        val first = CryptoManager().apply { loadOrCreate() }
        val identity = first.prepareLinkIdentity(linkSecret = null)
        assertNull(identity.keyProof)

        val restarted = CryptoManager().apply {
            loadOrCreate(identity.privateKeys)
            setLocalUserId(CoreTestDevice.ACCOUNT)
        }
        val subject = published(restarted)
        assertEquals(identity.deviceId, subject.deviceId)
        assertEquals(PeerDeviceVerdict.Vouched(old.id), verdictOf(subject, DeviceLinkEvidence.QrFingerprint(identity.fingerprint)))
    }

    /**
     * The keys are saved before the server hears of them, and `ConfirmDeviceLink` carries our id
     * and the proof. Mutation: save after the call — the order check fails.
     */
    @Test
    fun confirmSavesTheKeysFirstAndSendsTheProof() = runTest {
        val crypto = CryptoManager()
        val stub: DeviceLinkServiceCoroutineStub = mock()
        val grpc: GrpcClient = mock<GrpcClient>().also { whenever(it.deviceLink).thenReturn(stub) }
        val keystore: KeystoreManager = mock<KeystoreManager>().also { whenever(it.savePrivateKeys(any())).thenReturn(true) }
        val cursor: StreamCursorStore = mock()
        whenever(stub.confirmDeviceLink(any(), any())).thenReturn(
            ConfirmDeviceLinkResponse.newBuilder()
                .setTokens(AuthTokensResponse.newBuilder().setUserId(CoreTestDevice.ACCOUNT).setAccessToken(paseto(iat = 1_800_000_000)))
                .build(),
        )
        val secret = newDeviceLinkSecret()

        val deviceId = LinkDeviceUseCase(crypto, grpc, keystore, cursor, mock<UploadPreKeysUseCase>())
            .confirm(DeviceLinkQr.Offer("tok", fingerprint = null, secret = secret))

        val sent = argumentCaptor<ConfirmDeviceLinkRequest>()
        inOrder(keystore, stub) {
            verify(keystore).savePrivateKeys(any())
            verify(stub).confirmDeviceLink(sent.capture(), any())
        }
        assertEquals("tok", sent.firstValue.linkToken)
        assertEquals(deviceId, sent.firstValue.deviceId)
        assertEquals(32, sent.firstValue.linkKeyProof.size())
        verify(cursor).save("1800000000000-0")
    }

    /** Flow B up to the poll: our request is in, the stubs answer as told. */
    private inner class Join(vararg statuses: CheckJoinRequestStatusResponse.Status) {
        val crypto = CryptoManager()
        val link: DeviceLinkServiceCoroutineStub = mock()
        val auth: AuthServiceCoroutineStub = mock()
        val keystore: KeystoreManager = mock<KeystoreManager>().also { whenever(it.savePrivateKeys(any())).thenReturn(true) }
        private val grpc: GrpcClient = mock<GrpcClient>().also {
            whenever(it.deviceLink).thenReturn(link)
            whenever(it.auth).thenReturn(auth)
            whenever(it.veilPort).thenReturn(null)
        }
        val useCase = LinkDeviceUseCase(crypto, grpc, keystore, mock(), mock<UploadPreKeysUseCase>())
        val sent = argumentCaptor<JoinRequestPayload>()
        private val answers = statuses.map { CheckJoinRequestStatusResponse.newBuilder().setStatus(it).build() }

        suspend fun submitted(): String {
            whenever(link.submitJoinRequest(sent.capture(), any())).thenReturn(JoinRequestAck.getDefaultInstance())
            whenever(link.checkJoinRequestStatus(any(), any()))
                .thenReturn(answers.first(), *answers.drop(1).toTypedArray())
            return useCase.submitJoinRequest().second
        }

        suspend fun signInAnswers() {
            val tokens = AuthTokensResponse.newBuilder().setUserId(CoreTestDevice.ACCOUNT).setAccessToken(paseto(iat = 1_800_000_000))
            whenever(auth.authenticateDevice(any(), any())).thenReturn(AuthenticateDeviceResponse.newBuilder().setTokens(tokens).build())
        }
    }

    /**
     * The tokens come from signing in, under the key the request named. Mutations: sign in while
     * pending; take tokens from the status — there are none in it here.
     */
    @Test
    fun aJoinedDeviceSignsInWithTheKeyItAskedWith() = runTest {
        val join = Join(STATUS_PENDING, STATUS_APPROVED)
        val deviceId = join.submitted()
        join.signInAnswers()

        assertFalse(join.useCase.pollJoinRequest(deviceId))
        verify(join.auth, never()).authenticateDevice(any(), any())
        assertTrue(join.useCase.pollJoinRequest(deviceId))

        val signIn = argumentCaptor<AuthenticateDeviceRequest>()
        verify(join.auth).authenticateDevice(signIn.capture(), any())
        val r = signIn.firstValue
        assertEquals(deviceId, r.deviceId)
        val spki = byteArrayOf(0x30, 0x2a, 0x30, 5, 6, 3, 0x2b, 0x65, 0x70, 3, 0x21, 0) + join.sent.firstValue.verifyingKey.toByteArray()
        val verifier = Signature.getInstance("Ed25519").apply {
            initVerify(KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(spki)))
            update("${r.deviceId}${r.timestamp}".toByteArray())
        }
        assertTrue(verifier.verify(r.signature.toByteArray()))
        verify(join.keystore).saveTokens(any(), eq(deviceId))
    }

    /** Mutation: forget the approval — the second poll asks, hears "expired" and ends the join. */
    @Test
    fun aSignInThatDidNotGetThroughIsTriedAgainWithoutAsking() = runTest {
        val join = Join(STATUS_APPROVED, STATUS_EXPIRED)
        val deviceId = join.submitted()
        whenever(join.auth.authenticateDevice(any(), any())).thenThrow(io.grpc.Status.UNAVAILABLE.asRuntimeException())
        val first = runCatching { join.useCase.pollJoinRequest(deviceId) }.exceptionOrNull()
        assertTrue("$first", first is io.grpc.StatusRuntimeException)

        reset(join.auth)
        join.signInAnswers()
        assertTrue(join.useCase.pollJoinRequest(deviceId))
        verify(join.link, times(1)).checkJoinRequestStatus(any(), any())
    }

    /** Mutation: drop a code from `REFUSALS` — the wait goes on for ten minutes. */
    @Test
    fun aRefusedSignInEndsTheJoin() = runTest {
        val named = Metadata().apply { put(Metadata.Key.of("construct-device-refusal", Metadata.ASCII_STRING_MARSHALLER), "1") }
        val refusals = listOf(
            io.grpc.Status.INVALID_ARGUMENT.asRuntimeException(),
            io.grpc.Status.UNAUTHENTICATED.asRuntimeException(),
            io.grpc.Status.UNAUTHENTICATED.asRuntimeException(named),
            io.grpc.Status.PERMISSION_DENIED.asRuntimeException(),
        )
        for (refusal in refusals) {
            val join = Join(STATUS_APPROVED)
            val deviceId = join.submitted()
            whenever(join.auth.authenticateDevice(any(), any())).thenThrow(refusal)
            val error = runCatching { join.useCase.pollJoinRequest(deviceId) }.exceptionOrNull()
            assertTrue("$refusal: $error", error is JoinSignInRefused)
        }
    }

    /** One device's approval is not another's. Mutation: keep a flag instead of the id. */
    @Test
    fun anApprovalIsOfTheDeviceThatAsked() = runTest {
        val join = Join(STATUS_APPROVED, STATUS_PENDING)
        val first = join.submitted()
        whenever(join.auth.authenticateDevice(any(), any())).thenThrow(io.grpc.Status.UNAVAILABLE.asRuntimeException())
        runCatching { join.useCase.pollJoinRequest(first) }
        val second = join.useCase.submitJoinRequest().second
        assertFalse(join.useCase.pollJoinRequest(second))
    }

    /**
     * The server issues PASETO v4.public; the first emulator link, reading a JWT, logged "no
     * stream checkpoint". A JWT is not read at all. Mutation: read the JWT layout — reddens.
     */
    @Test
    fun theStreamStartsAtTheTokensIssueTime() {
        assertEquals("1800000000000-0", LinkDeviceUseCase.checkpointCursor(paseto(iat = 1_800_000_000)))
        assertNull(LinkDeviceUseCase.checkpointCursor(paseto(iat = 0)))
        assertNull(LinkDeviceUseCase.checkpointCursor("v4.public.AAAA"))
        assertNull(LinkDeviceUseCase.checkpointCursor(jwt(iat = 1_800_000_000)))
    }

    /** The server's layout (`construct-auth`): "v4.public." + base64url(nonce(32) || claims || signature(64)). */
    private fun paseto(iat: Long): String {
        val claims = """{"sub":"x","jti":"j","exp":${iat + 900},"iat":$iat,"iss":"construct"}""".toByteArray()
        val payload = ByteArray(32) { 7 } + claims + ByteArray(64) { 9 }
        return "v4.public." + Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
    }

    /** A legacy RS256-shaped token: refused, as everywhere on Android. */
    private fun jwt(iat: Long): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        return enc.encodeToString("""{"alg":"none"}""".toByteArray()) + "." +
            enc.encodeToString("""{"sub":"x","iat":$iat}""".toByteArray()) + ".sig"
    }
}
