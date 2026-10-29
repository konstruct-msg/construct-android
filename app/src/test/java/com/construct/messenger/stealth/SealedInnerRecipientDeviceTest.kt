package com.construct.messenger.stealth

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.construct.messenger.crypto.CryptoManager
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import shared.proto.core.v1.EnvelopeOuterClass.SealedInner
import uniffi.construct_core.deriveDeviceId

/**
 * TODO 161. A sealed envelope names the one device it is for, and that device is the one whose key
 * it is sealed to. Unnamed, the server delivers it to every device of the recipient, and each one
 * but the right one gets a copy it cannot open. **Canon:** iOS `buildSealedInner` (`recipientDevice`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SealedInnerRecipientDeviceTest {

    private val crypto: CryptoManager = mock()
    private val service = StealthSenderService(
        context = ApplicationProvider.getApplicationContext(),
        grpcClient = mock(),
        cryptoManager = crypto,
        serverKeys = mock(),
        policy = mock(),
        wallet = mock(),
        blindTokens = mock(),
        addressBook = mock(),
        intake = mock(),
        peerDevices = mock(),
    )

    /** Mutation: drop `setRecipientDevice`, or derive it from another key — this reddens. */
    @Test
    fun `the envelope names the device whose key it is sealed to`() = runTest {
        // The core's own derivation, so the test holds the rule and not a stand-in for it.
        whenever(crypto.deriveDeviceIdFromIdentity(any())).thenAnswer { deriveDeviceId(it.arguments[0] as ByteArray) }
        val second = ByteArray(32) { (it + 7).toByte() }

        val inner = SealedInner.parseFrom(
            service.buildSealedInner(
                recipientUserId = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                recipientIdentityKey = second,
                encryptedPayload = byteArrayOf(1),
                contentType = SealedEnvelopeType.GENERIC,
                sessionEnvelope = byteArrayOf(9, 9),
            ),
        )

        assertTrue(inner.recipientDevice.isNotEmpty())
        assertEquals(deriveDeviceId(second), inner.recipientDevice)
    }
}
