package com.construct.messenger.devicelink

import com.construct.messenger.R
import com.construct.messenger.crypto.CryptoManager
import com.construct.messenger.security.CoreTestDevice
import com.construct.messenger.security.PeerDeviceVetting
import com.construct.messenger.service.SessionManager.PeerBundle
import com.construct.messenger.data.api.GrpcClient
import com.construct.messenger.data.api.StreamCursorStore
import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.domain.usecase.LinkDeviceUseCase
import com.construct.messenger.domain.usecase.UploadPreKeysUseCase
import com.construct.messenger.viewmodel.JoinAccountViewModel
import io.grpc.Metadata
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
import shared.proto.services.v1.AuthServiceOuterClass.CheckJoinRequestStatusResponse.Status.STATUS_REJECTED
import shared.proto.services.v1.AuthServiceOuterClass.ConfirmDeviceLinkRequest
import shared.proto.services.v1.AuthServiceOuterClass.ConfirmDeviceLinkResponse
import shared.proto.services.v1.AuthServiceOuterClass.JoinRequestAck
import shared.proto.services.v1.AuthServiceOuterClass.JoinRequestPayload
import shared.proto.services.v1.DeviceLinkServiceGrpcKt.DeviceLinkServiceCoroutineStub
import uniffi.construct_core.BinaryKeyBundle
import uniffi.construct_core.DeviceJoinEnd
import uniffi.construct_core.DeviceLinkEvidence
import uniffi.construct_core.DeviceLinkQr
import uniffi.construct_core.PeerDeviceVerdict
import uniffi.construct_core.newDeviceLinkSecret
import uniffi.construct_core.readDeviceLinkQr

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
        val cursor: StreamCursorStore = mock()
        val useCase = LinkDeviceUseCase(crypto, grpc, keystore, cursor, mock<UploadPreKeysUseCase>())
        var qr = ""
        val sent = argumentCaptor<JoinRequestPayload>()
        private val answers = statuses.map { CheckJoinRequestStatusResponse.newBuilder().setStatus(it).build() }

        suspend fun submitted(): String {
            whenever(link.submitJoinRequest(sent.capture(), any())).thenReturn(JoinRequestAck.getDefaultInstance())
            whenever(link.checkJoinRequestStatus(any(), any()))
                .thenReturn(answers.first(), *answers.drop(1).toTypedArray())
            return useCase.submitJoinRequest().also { qr = it.first }.second
        }

        suspend fun signInAnswers() {
            val tokens = AuthTokensResponse.newBuilder().setUserId(CoreTestDevice.ACCOUNT).setAccessToken(paseto(iat = 1_800_000_000))
            whenever(auth.authenticateDevice(any(), any())).thenReturn(AuthenticateDeviceResponse.newBuilder().setTokens(tokens).build())
        }
    }

    /** [LinkDeviceUseCase.awaitJoin] on the test's clock; every time it signed in is counted. */
    private suspend fun TestScope.awaited(join: Join, deviceId: String): Pair<DeviceJoinEnd?, Int> {
        var signedIn = 0
        val end = join.useCase.awaitJoin(deviceId, nowMs = { testScheduler.currentTime }) { signedIn++ }
        return end to signedIn
    }

    /**
     * The tokens come from signing in, under the key the request named. Mutations: take tokens from
     * the status — there are none in it here; sign another message; do not say it is in.
     */
    @Test
    fun aJoinedDeviceSignsInWithTheKeyItAskedWith() = runTest {
        val join = Join(STATUS_PENDING, STATUS_APPROVED)
        val deviceId = join.submitted()
        join.signInAnswers()

        assertEquals(null to 1, awaited(join, deviceId))
        assertEquals(6_000, testScheduler.currentTime)

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
        verify(join.cursor).save("1800000000000-0")
    }

    /** Our QR names the keys we asked to join with, and the platform the server was told. */
    @Test
    fun theQrIsOfTheKeysWeAskedWith() = runTest {
        val join = Join(STATUS_PENDING)
        val deviceId = join.submitted()
        val qr = readDeviceLinkQr(join.qr) as DeviceLinkQr.JoinRequest
        assertEquals(deviceId, qr.deviceId)
        assertArrayEquals(join.sent.firstValue.identityPublic.toByteArray(), qr.identityPublic)
        assertEquals(join.sent.firstValue.platform, qr.platform)
        assertEquals(join.sent.firstValue.deviceName, qr.name)
    }

    /** What a call answered reaches the rule. Mutations: any failure as no answer; as a refusal. */
    @Test
    fun aSignInIsTriedAgainUnlessRefused() = runTest {
        val join = Join(STATUS_APPROVED, STATUS_EXPIRED)
        val deviceId = join.submitted()
        val named = Metadata().apply { put(Metadata.Key.of("construct-device-refusal", Metadata.ASCII_STRING_MARSHALLER), "1") }
        whenever(join.auth.authenticateDevice(any(), any()))
            .thenThrow(io.grpc.Status.UNAVAILABLE.asRuntimeException())
            .thenThrow(io.grpc.Status.UNAUTHENTICATED.asRuntimeException(named))

        assertEquals(DeviceJoinEnd.SIGN_IN_REFUSED to 0, awaited(join, deviceId))
        verify(join.link, times(1)).checkJoinRequestStatus(any(), any())
        verify(join.auth, times(2)).authenticateDevice(any(), any())
    }

    /** Signed in, and the prekey upload did not get through: not a refusal, it is tried again. */
    @Test
    fun aSetUpThatDidNotGetThroughIsTriedAgain() = runTest {
        val join = Join(STATUS_APPROVED)
        val deviceId = join.submitted()
        join.signInAnswers()
        var failed = false
        whenever(join.keystore.saveTokens(any(), any())).then {
            if (!failed) { failed = true; throw io.grpc.Status.PERMISSION_DENIED.asRuntimeException() } else Unit
        }
        assertEquals(null to 1, awaited(join, deviceId))
        verify(join.auth, times(2)).authenticateDevice(any(), any())
    }

    /** The status's number reaches the rule, and a status that did not come is asked again. */
    @Test
    fun theStatusReachesTheRule() = runTest {
        val rejected = Join(STATUS_REJECTED)
        assertEquals(DeviceJoinEnd.REJECTED to 0, awaited(rejected, rejected.submitted()))
        verify(rejected.auth, never()).authenticateDevice(any(), any())

        val join = Join(STATUS_APPROVED)
        val deviceId = join.submitted()
        join.signInAnswers()
        reset(join.link)
        whenever(join.link.checkJoinRequestStatus(any(), any()))
            .thenThrow(io.grpc.Status.UNAVAILABLE.asRuntimeException())
            .thenReturn(CheckJoinRequestStatusResponse.newBuilder().setStatus(STATUS_APPROVED).build())
        assertEquals(null to 1, awaited(join, deviceId))
    }

    /** The clock reaches the rule: nobody answers, and the wait ends by itself. */
    @Test
    fun itEndsWithoutAnAnswer() = runTest {
        val join = Join(STATUS_APPROVED)
        val deviceId = join.submitted()
        whenever(join.auth.authenticateDevice(any(), any())).thenThrow(io.grpc.Status.UNAVAILABLE.asRuntimeException())
        assertEquals(DeviceJoinEnd.LET_IN_UNREACHABLE to 0, awaited(join, deviceId))
    }

    @Test
    fun eachEndIsItsOwnSentence() {
        assertEquals(DeviceJoinEnd.entries.size, DeviceJoinEnd.entries.map(JoinAccountViewModel::errorFor).toSet().size)
        assertEquals(R.string.device_link_expired, JoinAccountViewModel.errorFor(DeviceJoinEnd.EXPIRED))
        assertEquals(R.string.device_link_sign_in_refused, JoinAccountViewModel.errorFor(DeviceJoinEnd.SIGN_IN_REFUSED))
        assertEquals(R.string.device_link_rejected, JoinAccountViewModel.errorFor(DeviceJoinEnd.REJECTED))
    }

    /** The server's layout (`construct-auth`): "v4.public." + base64url(nonce(32) || claims || signature(64)). */
    private fun paseto(iat: Long): String {
        val claims = """{"sub":"x","jti":"j","exp":${iat + 900},"iat":$iat,"iss":"construct"}""".toByteArray()
        val payload = ByteArray(32) { 7 } + claims + ByteArray(64) { 9 }
        return "v4.public." + Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
    }
}
