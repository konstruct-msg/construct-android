package com.construct.messenger.devicelink

import com.construct.messenger.crypto.CryptoManager
import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.data.repository.DevicesRepository
import com.construct.messenger.data.repository.DevicesRepository.LinkResult
import com.construct.messenger.security.CoreTestDevice
import com.construct.messenger.security.PeerDeviceVetting
import com.construct.messenger.service.SessionManager
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import uniffi.construct_core.DeviceLinkEvidence
import uniffi.construct_core.PeerDeviceVerdict
import uniffi.construct_core.newDeviceLinkSecret

/**
 * The account device's half of a link, through the real core: what it signs, and when it refuses.
 * The certificate and its check are the core's; what can go wrong here is waiting on the wrong
 * device, signing from the wrong evidence, or retrying a refusal. **Canon:** iOS
 * `DeviceCertificateLinkTests` (flow A/B).
 */
class DeviceCertificateIssuerTest {
    private val old = CoreTestDevice.create()
    private val new = CoreTestDevice.create()
    private val peer = CoreTestDevice.create()

    private val devices: DevicesRepository = mock()
    private val sessionManager: SessionManager = mock()
    private val keystore: KeystoreManager = mock<KeystoreManager>().also { whenever(it.getUserId()).thenReturn(ACCOUNT) }
    private val crypto: CryptoManager = mock<CryptoManager>().also {
        whenever(it.issueDeviceCertificate(any(), any())).thenAnswer { call ->
            old.core.issueDeviceCertificate(call.getArgument(0), call.getArgument(1))
        }
    }

    private fun TestScope.issuer() = DeviceCertificateIssuer(devices, sessionManager, crypto, keystore, backgroundScope)

    /** The certificate [devices] was handed for [new], as a peer that trusts [old] reads it. */
    private suspend fun publishedVerdict(): PeerDeviceVerdict {
        val certificate = argumentCaptor<ByteArray>()
        verify(devices).publishCertificate(eq(new.id), certificate.capture())
        val offered = PeerDeviceVetting.offered(listOf(old.served(), new.served(certificate.firstValue)))
        return peer.core.judgePeerDevices(listOf(old.id), offered)[1]
    }

    /**
     * Flow B: the new device's keys are not in the directory at first (it publishes them after the
     * link); the issuer waits, then signs what the QR fingerprint names. Mutation: give up when the
     * first fetch has no keys — nothing is published.
     */
    @Test
    fun flowBWaitsForTheKeysThenVouches() = runTest {
        whenever(sessionManager.discoverOwnDeviceBundles(ACCOUNT))
            .thenReturn(listOf(old.served()))
            .thenReturn(listOf(old.served(), new.served()))

        issuer().vouchNow(new.id, DeviceLinkEvidence.QrFingerprint(new.fingerprint))

        assertEquals(PeerDeviceVerdict.Vouched(old.id), publishedVerdict())
    }

    /**
     * Flow A: the proof the new device sent under our QR secret is the evidence. Mutation: vouch
     * on `Pending` — the token's device is not known yet and nothing is signed for it.
     */
    @Test
    fun flowAVouchesWithTheProofUnderOurSecret() = runTest {
        val secret = newDeviceLinkSecret()
        whenever(devices.linkResult("tok"))
            .thenReturn(LinkResult.Pending)
            .thenReturn(LinkResult.Confirmed(new.id, new.core.deviceLinkKeyProof(secret)))
        whenever(sessionManager.discoverOwnDeviceBundles(ACCOUNT)).thenReturn(listOf(old.served(), new.served()))

        issuer().awaitLinkNow("tok", secret)

        assertEquals(PeerDeviceVerdict.Vouched(old.id), publishedVerdict())
    }

    /**
     * The directory serves keys other than the ones the QR named — the substitution the
     * certificate exists to catch: refused, nothing published, no retry. Mutation: retry on
     * `EvidenceMismatch` — the call never returns.
     */
    @Test
    fun keysOtherThanTheQrNamedAreNeverSigned() = runTest {
        val rogue = CoreTestDevice.create()
        // The server answers for the new device's id with another device's keys.
        whenever(sessionManager.discoverOwnDeviceBundles(ACCOUNT))
            .thenReturn(listOf(rogue.served().copy(deviceId = new.id)))

        issuer().vouchNow(new.id, DeviceLinkEvidence.QrFingerprint(new.fingerprint))

        verify(devices, never()).publishCertificate(any(), any())
    }

    /** A new device older than certificates sends no proof: nothing to sign. */
    @Test
    fun noProofNoCertificate() = runTest {
        whenever(devices.linkResult("tok")).thenReturn(LinkResult.Confirmed(new.id, ByteArray(0)))

        issuer().awaitLinkNow("tok", newDeviceLinkSecret())

        verify(sessionManager, never()).discoverOwnDeviceBundles(any())
        verify(devices, never()).publishCertificate(any(), any())
    }

    /** A failed publish is tried again. */
    @Test
    fun aFailedPublishIsRetried() = runTest {
        whenever(sessionManager.discoverOwnDeviceBundles(ACCOUNT)).thenReturn(listOf(new.served()))
        whenever(devices.publishCertificate(eq(new.id), any()))
            .thenThrow(RuntimeException("unavailable"))
            .thenReturn(Unit)

        issuer().vouchNow(new.id, DeviceLinkEvidence.QrFingerprint(new.fingerprint))

        verify(devices, org.mockito.kotlin.times(2)).publishCertificate(eq(new.id), any())
    }

    private companion object {
        const val ACCOUNT = CoreTestDevice.ACCOUNT
    }
}
