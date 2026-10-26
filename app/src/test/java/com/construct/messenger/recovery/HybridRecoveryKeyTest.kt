package com.construct.messenger.recovery

import com.construct.messenger.crypto.CryptoManager
import com.construct.messenger.data.api.GrpcClient
import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.domain.usecase.RecoverAccountUseCase
import com.construct.messenger.domain.usecase.UploadPreKeysUseCase
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import shared.proto.services.v1.AuthServiceGrpcKt.AuthServiceCoroutineStub
import shared.proto.services.v1.AuthServiceOuterClass.AuthTokensResponse
import shared.proto.services.v1.AuthServiceOuterClass.GetRecoveryStatusResponse
import shared.proto.services.v1.AuthServiceOuterClass.RecoverAccountRequest
import shared.proto.services.v1.AuthServiceOuterClass.RecoverAccountResponse
import shared.proto.services.v1.AuthServiceOuterClass.SetRecoveryKeyRequest
import shared.proto.services.v1.AuthServiceOuterClass.SetRecoveryKeyResponse
import uniffi.construct_core.deriveRecoveryKeypair
import uniffi.construct_core.mnemonicToSeed

/**
 * The hybrid recovery key (Ed25519 + ML-DSA-65, construct-docs todo 222), through the real core.
 * What can go wrong on Android: the address changing (every invite and contact names it), a claim
 * not bound to the device it registers, or an upgrade sent for another account's phrase.
 *
 * The Ed25519 half of a hybrid signature is deterministic, so a signature is checked by signing
 * the same fields again and comparing that half; the ML-DSA half is the server's to verify.
 */
class HybridRecoveryKeyTest {
    private val crypto = CryptoManager()
    private val phrase = crypto.generateMnemonic(12)
    private val stub: AuthServiceCoroutineStub = mock<AuthServiceCoroutineStub>().also {
        whenever(it.withDeadlineAfter(any(), any())).thenReturn(it)
    }
    private val grpc: GrpcClient = mock<GrpcClient>().also { whenever(it.auth).thenReturn(stub) }
    private val keystore: KeystoreManager = mock<KeystoreManager>().also {
        whenever(it.getUserId()).thenReturn(ACCOUNT)
        whenever(it.savePrivateKeys(any())).thenReturn(true)
    }
    private val repository = RecoveryRepository(grpc, crypto, keystore, mock())

    /** Mutation: the address as the key's last 32 bytes — every existing account changes address. */
    @Test
    fun theAddressIsTheEd25519KeyTheAccountAlreadyHas() {
        val before = deriveRecoveryKeypair(mnemonicToSeed(phrase)).publicKey
        assertEquals(1984, crypto.recoveryPublicKey(phrase).size)
        assertArrayEquals(before, repository.publicKeyOf(phrase))
    }

    /** Mutation: send the address (32 bytes) as the key — the server would set an Ed25519 key. */
    @Test
    fun setupSendsTheHybridKeyAndItsSignature() = runTest {
        whenever(stub.setRecoveryKey(any(), any())).thenReturn(SetRecoveryKeyResponse.getDefaultInstance())

        val (outcome, address) = repository.send(phrase, ACCOUNT)

        val sent = argumentCaptor<SetRecoveryKeyRequest>().also { verify(stub).setRecoveryKey(it.capture(), any()) }.firstValue
        assertEquals(SetUpOutcome.DONE, outcome)
        assertArrayEquals(crypto.recoveryPublicKey(phrase), sent.recoveryPublicKey.toByteArray())
        assertEquals(HYBRID_SIGNATURE_BYTES, sent.setupSignature.size())
        val again = crypto.signRecoverySetup(phrase, ACCOUNT, sent.timestamp)
        assertArrayEquals(again.signature.copyOf(64), sent.setupSignature.toByteArray().copyOf(64))
        assertArrayEquals(repository.publicKeyOf(phrase), address)
    }

    /**
     * The claim names the device it registers and carries no `challenge`. Mutation: sign with
     * another device id, or keep the old challenge — the comparison or the emptiness check fails.
     */
    @Test
    fun theClaimIsBoundToTheNewDevicesKeys() = runTest {
        whenever(stub.recoverAccount(any(), any())).thenReturn(
            RecoverAccountResponse.newBuilder().setTokens(AuthTokensResponse.newBuilder().setUserId(ACCOUNT)).build(),
        )

        RecoverAccountUseCase(crypto, grpc, keystore, mock<UploadPreKeysUseCase>())(ACCOUNT, phrase)

        val sent = argumentCaptor<RecoverAccountRequest>().also { verify(stub).recoverAccount(it.capture(), any()) }.firstValue
        val keys = sent.newDevice.publicKeys
        assertEquals("", sent.challenge)
        assertArrayEquals(crypto.recoveryPublicKey(phrase), sent.recoveryPublicKey.toByteArray())
        assertTrue(kotlin.math.abs(System.currentTimeMillis() / 1000 - sent.timestamp) < 60)
        assertEquals(HYBRID_SIGNATURE_BYTES, sent.recoverySignature.size())
        val again = crypto.signRecoveryClaim(
            phrase, sent.timestamp, sent.newDevice.deviceId, keys.verifyingKey.toByteArray(), keys.identityPublic.toByteArray(),
        )
        assertArrayEquals(again.signature.copyOf(64), sent.recoverySignature.toByteArray().copyOf(64))
        verify(keystore).saveOwnAccountAddress(repository.publicKeyOf(phrase))
    }

    /** An Ed25519-only account is upgraded by its own phrase only. Mutation: skip a check — reddens. */
    @Test
    fun onlyTheAccountsOwnEd25519KeyIsUpgraded() = runTest {
        whenever(stub.setRecoveryKey(any(), any())).thenReturn(SetRecoveryKeyResponse.getDefaultInstance())
        val own = fingerprint(repository.publicKeyOf(phrase))

        repository.upgradeIfEd25519Only(phrase, RecoveryStatus(isSetup = true, fingerprint = own, isHybrid = true))
        repository.upgradeIfEd25519Only(phrase, RecoveryStatus(isSetup = false, fingerprint = null))
        val other = fingerprint(repository.publicKeyOf(crypto.generateMnemonic(12)))
        repository.upgradeIfEd25519Only(phrase, RecoveryStatus(isSetup = true, fingerprint = other))
        verify(stub, never()).setRecoveryKey(any(), any())

        repository.upgradeIfEd25519Only(phrase, RecoveryStatus(isSetup = true, fingerprint = own))
        val sent = argumentCaptor<SetRecoveryKeyRequest>().also { verify(stub).setRecoveryKey(it.capture(), any()) }.firstValue
        assertEquals(1984, sent.recoveryPublicKey.size())
    }

    /** The server's `is_hybrid` reaches the status. Mutation: drop it — every account looks Ed25519-only. */
    @Test
    fun theStatusCarriesIsHybrid() = runTest {
        whenever(stub.getRecoveryStatus(any(), any())).thenReturn(
            GetRecoveryStatusResponse.newBuilder().setIsSetup(true).setIsHybrid(true).build(),
        )
        assertTrue(repository.status().isHybrid)
    }

    /**
     * The once-only prompt shows for an Ed25519-only key, unless a held phrase still owes its copy
     * (that copy upgrades). Mutation: ignore [HeldPhrase.HELD] or `is_hybrid` — reddens.
     */
    @Test
    fun theUpgradePromptIsForAnEd25519KeyWithNoCopyOwed() {
        val ed25519 = RecoveryStatus(isSetup = true, fingerprint = "ab", isHybrid = false)
        assertTrue(upgradeNeeded(ed25519, HeldPhrase.NONE))
        assertTrue(upgradeNeeded(ed25519, HeldPhrase.LOST))
        assertFalse(upgradeNeeded(ed25519, HeldPhrase.HELD))
        assertFalse(upgradeNeeded(ed25519.copy(isHybrid = true), HeldPhrase.NONE))
        assertFalse(upgradeNeeded(RecoveryStatus(isSetup = false, fingerprint = null), HeldPhrase.NONE))
        assertFalse(upgradeNeeded(null, HeldPhrase.NONE))
    }

    private fun fingerprint(key: ByteArray) =
        key.joinToString("") { "%02X".format(it) }.take(32).chunked(4).joinToString(" ")

    private companion object {
        const val ACCOUNT = "3b0c2f9e-5d1a-4e7b-9c84-2a6f1d0e7b35"
        /** Ed25519 64 ‖ ML-DSA-65 3309. */
        const val HYBRID_SIGNATURE_BYTES = 3373
    }
}
