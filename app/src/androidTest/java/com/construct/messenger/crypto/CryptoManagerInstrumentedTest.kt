package com.construct.messenger.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.construct_core.BinaryKeyBundle
import uniffi.construct_core.PqHandshake
import uniffi.construct_core.RegistrationBundleFields
import uniffi.construct_core.WirePayload
import uniffi.construct_core.wirePayloadPack
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

        assertNotEquals(first.identityPublic, second.identityPublic)
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
        assertEquals(first.privateKey, second.privateKey)
        assertEquals(first.publicKey, second.publicKey)

        val otherMnemonic = crypto.generateMnemonic(12)
        val third = crypto.deriveRecoveryKeypair(otherMnemonic)
        assertNotEquals(first.privateKey, third.privateKey)
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
            verifyRecoverySignature(bundle.verifyingKey, message, signature.toUByteList()),
        )
        crypto.close()
    }

    /**
     * A PQXDH v2 handshake between two real cores, both directions. Each side is shown the other
     * the way the key service serves it after PQXDH v2 — classic prekeys, a Kyber SPK signed over
     * its `created_at`, a Kyber one-time key, the hybrid identity and its binding — and the
     * responder opens from the packed wire payload, as [ResponderInitUseCase] does.
     */
    @Test
    fun pqxdhV2_handshakeRoundtripsBothDirections() {
        val alice = CryptoManager()
        val bob = CryptoManager()
        val aliceFields = alice.loadOrCreate()
        val bobFields = bob.loadOrCreate()
        alice.setLocalUserId("alice-account")
        bob.setLocalUserId("bob-account")
        // Each side names the other by the device id that side's core was bound to: the AD binds
        // both, so a session opened under any other name cannot decrypt.
        val aliceId = requireNotNull(alice.currentDeviceId())
        val bobId = requireNotNull(bob.currentDeviceId())

        val bobBundle = bob.pqxdhTestBundle(bobFields, withOneTimeKey = true)
        alice.initSession(bobId, bobBundle)
        val first = alice.encryptMessage(bobId, "hello from alice")
        assertEquals("the first message carries the ML-KEM-1024 ciphertext", 1568, first.kemCiphertext.size)
        assertEquals("the one-time key is preferred", bobBundle.kyberOneTimePrekeyId, first.kyberPrekeyId)

        val wire = wirePayloadPack(
            WirePayload(
                dhPublicKey = first.ephemeralPublicKey,
                messageNumber = first.messageNumber,
                oneTimePrekeyId = first.oneTimePrekeyId,
                kyberOtpkId = first.kyberPrekeyId,
                previousChainLength = 0u,
                suiteId = first.suiteId,
                kemCiphertext = first.kemCiphertext,
                sealedBox = first.content,
                pqMessageEpoch = first.pqMessageEpoch,
                pqRatchetField = first.pqRatchetField,
            ),
        ).toByteArray()
        val init = bob.initReceivingSessionFromWirePayload(aliceId, alice.pqxdhTestBundle(aliceFields), wire)
        assertEquals("hello from alice", init.decryptedMessage.toUtf8String())
        assertNotNull("the used one-time key was burned: the blob to persist comes back", init.kyberPrekeys)
        assertEquals(PqHandshake.INITIAL_V2, alice.sessionHealth(bobId)?.pqHandshake)
        assertEquals(PqHandshake.INITIAL_V2, bob.sessionHealth(aliceId)?.pqHandshake)

        // Reply path: Bob -> Alice over the now-established session.
        val reply = bob.encryptMessage(aliceId, "hi from bob")
        val decryptedReply = alice.decryptMessage(
            bobId,
            reply.ephemeralPublicKey.toByteArray(),
            reply.messageNumber,
            reply.content.toByteArray(),
            reply.suiteId,
            reply.pqMessageEpoch,
            reply.pqRatchetField.toByteArray(),
        )
        assertEquals("hi from bob", decryptedReply.plaintext.toUtf8String())

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
        hybridIdentityKey = hybrid.toUByteList(),
        hybridIdentitySignature = binding.toUByteList(),
    )
}

private fun List<UByte>.toByteArray(): ByteArray = ByteArray(size) { this[it].toByte() }
private fun ByteArray.toUByteList(): List<UByte> = map { it.toUByte() }
private fun List<UByte>.toUtf8String(): String = String(toByteArray(), Charsets.UTF_8)
