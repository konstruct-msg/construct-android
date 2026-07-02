package com.construct.messenger.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.construct_core.BinaryFirstMessage
import uniffi.construct_core.BinaryKeyBundle
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

    @Test
    fun encryptDecrypt_fullX3dhHandshakeRoundtripsBothDirections() {
        val alice = CryptoManager()
        val bob = CryptoManager()

        val aliceBundle = alice.loadOrCreate()
        val bobBundle = bob.loadOrCreate()
        // The core requires a local user id before init_session/encrypt_message/decrypt_message
        // — normally set by RegisterUseCase/LoginUseCase after a successful auth call.
        alice.setLocalUserId("alice")
        bob.setLocalUserId("bob")
        val bobOtpk = bob.generateOneTimePrekeys(1).single()

        val bobBinaryBundle = BinaryKeyBundle(
            identityPublic = bobBundle.identityPublic,
            signedPrekeyPublic = bobBundle.signedPrekeyPublic,
            signature = bobBundle.signature,
            verifyingKey = bobBundle.verifyingKey,
            suiteId = bobBundle.suiteId,
            oneTimePrekeyPublic = bobOtpk.publicKey,
            oneTimePrekeyId = bobOtpk.keyId,
            spkUploadedAt = 0uL,
            spkRotationEpoch = 0u,
            kyberSpkUploadedAt = 0uL,
            kyberSpkRotationEpoch = 0u,
            kyberPreKeyPublic = null,
            kyberOneTimePrekeyPublic = null,
            kyberOneTimePrekeyId = null,
            supportsPqRatchet = false,
        )

        val aliceSessionId = alice.initSession("bob", bobBinaryBundle)
        val firstMessage = alice.encryptMessage(aliceSessionId, "hello from alice")

        val aliceBinaryBundle = BinaryKeyBundle(
            identityPublic = aliceBundle.identityPublic,
            signedPrekeyPublic = aliceBundle.signedPrekeyPublic,
            signature = aliceBundle.signature,
            verifyingKey = aliceBundle.verifyingKey,
            suiteId = aliceBundle.suiteId,
            oneTimePrekeyPublic = null,
            oneTimePrekeyId = null,
            spkUploadedAt = 0uL,
            spkRotationEpoch = 0u,
            kyberSpkUploadedAt = 0uL,
            kyberSpkRotationEpoch = 0u,
            kyberPreKeyPublic = null,
            kyberOneTimePrekeyPublic = null,
            kyberOneTimePrekeyId = null,
            supportsPqRatchet = false,
        )
        val firstMessageForBob = BinaryFirstMessage(
            ephemeralPublicKey = firstMessage.ephemeralPublicKey,
            messageNumber = firstMessage.messageNumber,
            content = firstMessage.content,
            oneTimePrekeyId = firstMessage.oneTimePrekeyId,
        )

        val initResult = bob.initReceivingSession("alice", aliceBinaryBundle, firstMessageForBob)
        assertEquals("hello from alice", initResult.decryptedMessage.toUtf8String())

        // Reply path: Bob -> Alice over the now-established session.
        val reply = bob.encryptMessage(initResult.sessionId, "hi from bob")
        val decryptedReply = alice.decryptMessage(
            aliceSessionId,
            reply.ephemeralPublicKey.toByteArray(),
            reply.messageNumber,
            reply.content.toByteArray(),
        )
        assertEquals("hi from bob", decryptedReply.plaintext.toUtf8String())

        alice.close()
        bob.close()
    }
}

private fun List<UByte>.toByteArray(): ByteArray = ByteArray(size) { this[it].toByte() }
private fun ByteArray.toUByteList(): List<UByte> = map { it.toUByte() }
private fun List<UByte>.toUtf8String(): String = String(toByteArray(), Charsets.UTF_8)
