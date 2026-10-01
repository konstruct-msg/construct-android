package com.construct.messenger.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.construct_core.BinaryKeyBundle
import uniffi.construct_core.CfeAction
import uniffi.construct_core.CfeIncomingEvent
import uniffi.construct_core.PqHandshake
import uniffi.construct_core.RegistrationBundleFields
import uniffi.construct_core.SenderCertificate
import uniffi.construct_core.verifyPow
import uniffi.construct_core.verifyRecoverySignature

/**
 * Exercises [CryptoManager] against the real `libconstruct_core.so` (this is why these are
 * `androidTest`, not `test` — the native lib is built for Android ABIs only and cannot load
 * on the host JVM). Run with `./gradlew connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class CryptoManagerInstrumentedTest {

    @Test
    fun loadOrCreate_generatesNonEmptyKeyMaterial() {
        val crypto = CryptoManager()
        val bundle = crypto.loadOrCreate()

        assertTrue(bundle.identityPublic.isNotEmpty())
        assertTrue(bundle.signedPrekeyPublic.isNotEmpty())
        assertTrue(bundle.signature.isNotEmpty())
        assertTrue(bundle.verifyingKey.isNotEmpty())
        crypto.close()
    }

    @Test
    fun loadOrCreate_eachCallProducesADifferentIdentity() {
        val first = CryptoManager().loadOrCreate()
        val second = CryptoManager().loadOrCreate()

        assertFalse(first.identityPublic.contentEquals(second.identityPublic))
    }

    @Test
    fun computePow_producesASolutionTheCoreItselfAccepts() {
        val crypto = CryptoManager()
        val challenge = "instrumented-test-challenge"
        val difficulty = 4 // low on purpose — keep the test fast

        val solution = crypto.computePow(challenge, difficulty)

        assertTrue(verifyPow(challenge, solution, difficulty.toUInt()))
        crypto.close()
    }

    @Test
    fun generateMnemonic_deriveRecoveryKeypair_isDeterministic() {
        val crypto = CryptoManager()
        val mnemonic = crypto.generateMnemonic(12)
        assertEquals(12, mnemonic.trim().split(" ").size)

        val first = crypto.deriveRecoveryKeypair(mnemonic)
        val second = crypto.deriveRecoveryKeypair(mnemonic)
        assertArrayEquals(first.privateKey, second.privateKey)
        assertArrayEquals(first.publicKey, second.publicKey)

        val otherMnemonic = crypto.generateMnemonic(12)
        val third = crypto.deriveRecoveryKeypair(otherMnemonic)
        assertFalse(first.privateKey.contentEquals(third.privateKey))
        crypto.close()
    }

    /**
     * Validates the judgment call in [CryptoManager.signWithDeviceKey]: it repurposes the
     * `signRecoveryChallenge` FFI export (there's no dedicated bare Ed25519-sign function) for
     * the device-auth signature. This proves the signature actually verifies against the
     * device's own `verifyingKey` using the core's own verify function — not just "doesn't
     * crash".
     */
    @Test
    fun signWithDeviceKey_producesASignatureVerifiableWithTheDeviceVerifyingKey() {
        val crypto = CryptoManager()
        val bundle = crypto.loadOrCreate()
        val message = "device-1${System.currentTimeMillis() / 1000}"

        val signature = crypto.signWithDeviceKey(message)

        assertTrue(
            verifyRecoverySignature(bundle.verifyingKey, message, signature),
        )
        crypto.close()
    }

    /**
     * A PQXDH v2 handshake between two real cores, both directions. Each side is shown the other
     * the way the key service serves it after PQXDH v2 — classic prekeys, a Kyber SPK signed over
     * its `created_at`, a Kyber one-time key, the hybrid identity and its binding. The responder
     * opens the way the app does: the message goes to the core with the sender certificate a
     * server signed, the core asks for `OpenReceiving`, and the open checks that signature —
     * nothing is fetched (`decisions/first-message-opens-without-the-server.md`).
     */
    @Test
    fun pqxdhV2_handshakeRoundtripsBothDirections() {
        val alice = CryptoManager()
        val bob = CryptoManager()
        val aliceFields = alice.loadOrCreate()
        val bobFields = bob.loadOrCreate()
        alice.setLocalUserId("alice-account")
        bob.setLocalUserId("bob-account")
        // Every registered device has one (`KyberPrekeyService`); the initiator answers with it.
        alice.ensureHybridIdentityPublicKey()
        // Each side names the other by the device id that side's core was bound to: the AD binds
        // both, so a session opened under any other name cannot decrypt.
        val aliceId = requireNotNull(alice.currentDeviceId())
        val bobId = requireNotNull(bob.currentDeviceId())

        val bobBundle = bob.pqxdhTestBundle(bobFields, withOneTimeKey = true)
        alice.initSession(bobId, bobBundle)
        // The bare wire payload, as the send path hands it on: the first message on a new session
        // carries the ML-KEM-1024 ciphertext (1568 bytes) on top of everything else.
        val wire = alice.encryptToWire(bobId, "hello from alice".toByteArray())
        assertTrue("the first message carries the ML-KEM-1024 ciphertext (${wire.size} bytes)", wire.size > 1568)
        val server = TestCertificateServer()
        val certificate = server.certify(aliceId, aliceFields.identityPublic.let { key -> ByteArray(key.size) { key[it].toByte() } })
        val received = bob.handleEvent(
            CfeIncomingEvent.MessageReceived(
                messageId = "first-1",
                from = aliceId,
                data = wire,
                contentType = 1u,
                senderCertificate = certificate,
                envelopeSession = null,
            ),
        )
        // A fresh core has no ACK cache and asks the database first, as after any restart.
        val asked = (received.singleOrNull() as? CfeAction.CheckAckInDb)
            ?.let { bob.handleEvent(CfeIncomingEvent.AckDbResult(it.messageId, false)) }
            ?: received
        assertTrue("no session: the core asks for an open", asked.any { it is CfeAction.OpenReceiving && it.contactId == aliceId })

        val opened = bob.openReceiving(aliceId, listOf(server.verifyingKey))
        assertEquals(aliceId, opened.openedDevice)
        val decrypted = opened.actions.filterIsInstance<CfeAction.MessageDecrypted>().single()
        assertEquals("first-1", decrypted.messageId)
        assertNotNull("the used one-time key was burned: the blob to persist comes back", opened.kyberPrekeys)
        assertEquals(PqHandshake.INITIAL_V2, alice.sessionHealth(bobId)?.pqHandshake)
        assertEquals(PqHandshake.INITIAL_V2, bob.sessionHealth(aliceId)?.pqHandshake)

        // Reply path: Bob -> Alice over the now-established session.
        val reply = bob.encryptToWire(aliceId, "hi from bob".toByteArray())
        val decryptedReply = alice.decryptWirePayload(bobId, reply)
        assertEquals("hi from bob", decryptedReply.toUtf8String())

        alice.close()
        bob.close()
    }

    /** A bundle without the hybrid identity is refused before anything is created, and the
     * refusal is recognisable as "peer not post-quantum". */
    @Test
    fun pqxdhV2_aBundleWithoutTheHybridIdentityIsRefused() {
        val alice = CryptoManager()
        val bob = CryptoManager()
        alice.loadOrCreate()
        val bobFields = bob.loadOrCreate()
        alice.setLocalUserId("alice-account")
        bob.setLocalUserId("bob-account")
        // Every registered device has one (`KyberPrekeyService`); the initiator answers with it.
        alice.ensureHybridIdentityPublicKey()
        val bobId = requireNotNull(bob.currentDeviceId())
        val bundle = bob.pqxdhTestBundle(bobFields).copy(hybridIdentityKey = null, hybridIdentitySignature = null)

        val error = runCatching { alice.initSession(bobId, bundle) }.exceptionOrNull()
        assertTrue("expected PQ_REQUIRED, got $error", error != null && CryptoManager.isPeerNotPostQuantum(error))
        assertTrue("the refusal leaves no session behind", alice.getAllSessionContactIds().isEmpty())

        alice.close()
        bob.close()
    }
}

/**
 * This device's bundle as the key service would serve it after PQXDH v2. Everything is produced
 * by the core under test; nothing here signs or encapsulates by itself.
 */
private fun CryptoManager.pqxdhTestBundle(
    fields: RegistrationBundleFields,
    withOneTimeKey: Boolean = false,
): BinaryKeyBundle {
    val hybrid = ensureHybridIdentityPublicKey()
    val binding = signHybridIdentityBinding(hybrid)
    val spk = currentKyberSpkUpload() ?: run {
        beginKyberSpkRotation()
        commitKyberSpkRotation()
        requireNotNull(currentKyberSpkUpload())
    }
    val otpk = if (withOneTimeKey) generateKyberOneTimePrekeys(1).single() else null
    val now = System.currentTimeMillis().toULong() / 1000uL
    return BinaryKeyBundle(
        identityPublic = fields.identityPublic,
        signedPrekeyPublic = fields.signedPrekeyPublic,
        signature = fields.signature,
        verifyingKey = fields.verifyingKey,
        suiteId = fields.suiteId,
        oneTimePrekeyPublic = null,
        oneTimePrekeyId = null,
        spkUploadedAt = now,
        spkRotationEpoch = 1u,
        kyberSpkUploadedAt = now,
        kyberSpkRotationEpoch = 1u,
        kyberPreKeyPublic = spk.publicKey,
        kyberPreKeyId = spk.keyId,
        kyberPreKeyCreatedAt = spk.createdAt,
        kyberPreKeySignature = spk.signature,
        kyberPreKeyHybridSignature = spk.hybridSignature,
        kyberOneTimePrekeyPublic = otpk?.publicKey,
        kyberOneTimePrekeyId = otpk?.keyId,
        kyberOneTimePrekeyCreatedAt = otpk?.createdAt,
        kyberOneTimePrekeySignature = otpk?.signature,
        kyberOneTimePrekeyHybridSignature = otpk?.hybridSignature,
        hybridIdentityKey = hybrid,
        hybridIdentitySignature = binding,
    )
}

private fun ByteArray.toUtf8String(): String = String(this, Charsets.UTF_8)

/**
 * Signs sender certificates the way `identity-service` does: Ed25519 over `user_id ‖ domain ‖
 * identity_key ‖ device_id ‖ BE64(issued_at) ‖ BE64(expires_at)`, no separators. A core of its
 * own stands in for the server — its device signing key signs the bytes as they are — because the
 * platform ships no software Ed25519 key generator (only AndroidKeyStore's, which wants a keystore
 * spec). The open checks the signature against [verifyingKey] as it checks the real server's.
 */
private class TestCertificateServer {
    private val core = uniffi.construct_core.createCryptoCore()

    val verifyingKey: ByteArray = core.getRegistrationBundleFields().verifyingKey

    fun certify(deviceId: String, identityKey: ByteArray, account: String = "test-account"): SenderCertificate {
        val domain = "konstruct.test"
        val issued = System.currentTimeMillis() / 1000
        val expires = issued + 86_400
        val payload = java.io.ByteArrayOutputStream().apply {
            write(account.toByteArray())
            write(domain.toByteArray())
            write(identityKey)
            write(deviceId.toByteArray())
            write(java.nio.ByteBuffer.allocate(16).putLong(issued).putLong(expires).array())
        }.toByteArray()
        val signature = core.signBundleData(payload)
        return SenderCertificate(account, domain, identityKey, deviceId, issued, expires, signature)
    }
}
