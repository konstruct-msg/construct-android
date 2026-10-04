package com.construct.messenger.recovery

import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The silent recovery key (`decisions/recovery-key-backup-is-deferred-not-skipped.md`). What is
 * pinned is the order: the phrase is stored before the server hears of the key, a retry reuses it,
 * and it moves behind authentication only once the server took it. Each test names the mutation
 * that reddens it. **Canon:** iOS `RecoveryKeyProvisionerTests`.
 */
class RecoveryKeyProvisionerTest {

    private class FakeStore : RecoveryPhraseStore {
        override var canHold = true
        var pending: Pair<String, String>? = null
        var held: Pair<String, String>? = null
        var failStore = false
        var failPromote = false
        /** What was stored at the moment the upload ran — the order check. */
        var pendingAtUpload: String? = null

        override fun storePending(phrase: String, account: String): Boolean {
            if (failStore) return false
            pending = phrase to account
            return true
        }
        override fun pendingPhrase(account: String) = pending?.takeIf { it.second == account }?.first
        override fun promotePending(account: String): Boolean {
            val p = pending?.takeIf { it.second == account } ?: return false
            if (failPromote) return false
            held = p
            pending = null
            return true
        }
        override fun held(account: String) =
            if (held?.second == account) HeldPhrase.HELD else HeldPhrase.NONE
        override fun forgetPending() { pending = null }
        override fun forgetHeld() { held = null }
    }

    private val store = FakeStore()
    private var address: ByteArray? = null
    private var serverHasKey = false
    private var statusFails = false
    private var uploadOutcome = SetUpOutcome.DONE
    private var uploadThrows = false
    private val uploads = mutableListOf<String>()
    private var generated = 0

    /** A phrase's public key, as the fake derives it. */
    private fun keyOf(phrase: String) = phrase.toByteArray()

    private fun provisioner() = RecoveryKeyProvisioner(object : RecoveryKeyProvisioner.Dependencies {
        override fun knownAddress() = address
        override fun publicKeyOf(phrase: String) = keyOf(phrase)
        override suspend fun serverHasKey(): Boolean {
            if (statusFails) error("offline")
            return serverHasKey
        }
        override fun generatePhrase(): String {
            generated += 1
            return "phrase-$generated"
        }
        override suspend fun upload(phrase: String, userId: String): Pair<SetUpOutcome, ByteArray> {
            this@RecoveryKeyProvisionerTest.store.let { it.pendingAtUpload = it.pendingPhrase("u1") }
            uploads += phrase
            if (uploadThrows) error("offline")
            return uploadOutcome to keyOf(phrase)
        }
        override fun rememberAddress(publicKey: ByteArray) { address = publicKey }
        override val store: RecoveryPhraseStore = this@RecoveryKeyProvisionerTest.store
    })

    private fun ensure() = runBlocking { provisioner().ensureKey("u1") }

    /** Mutation: upload before `storePending` — the phrase is not in the vault when the server
     * takes its key. */
    @Test
    fun `the phrase is stored before the server hears of the key`() {
        assertEquals(RecoveryKeyProvisioner.Outcome.PROVISIONED, ensure())
        assertEquals("phrase-1", store.pendingAtUpload)
        assertEquals("phrase-1", store.held?.first)
        assertNull(store.pending)
        assertArrayEquals(keyOf("phrase-1"), address)
    }

    /** Mutation: generate a fresh phrase on every run — the retry sets a second key whose first
     * phrase is gone. */
    @Test
    fun `a retry reuses the pending phrase`() {
        uploadOutcome = SetUpOutcome.FAILED
        assertEquals(RecoveryKeyProvisioner.Outcome.DEFERRED, ensure())
        assertEquals("phrase-1", store.pending?.first)

        uploadOutcome = SetUpOutcome.DONE
        assertEquals(RecoveryKeyProvisioner.Outcome.PROVISIONED, ensure())
        assertEquals(listOf("phrase-1", "phrase-1"), uploads)
        assertEquals(1, generated)
    }

    @Test
    fun `an upload that throws keeps the phrase for the next attempt`() {
        uploadThrows = true
        assertEquals(RecoveryKeyProvisioner.Outcome.DEFERRED, ensure())
        assertEquals("phrase-1", store.pending?.first)
    }

    /** Another key on the server means this phrase names nothing; keeping it would offer the
     * person a "copy" of a key that recovers nothing. */
    @Test
    fun `another key on the server drops the pending phrase`() {
        uploadOutcome = SetUpOutcome.OTHER_PHRASE_SET
        assertEquals(RecoveryKeyProvisioner.Outcome.SET_ELSEWHERE, ensure())
        assertNull(store.pending)
        assertNull(store.held)
        assertNull(address)
    }

    @Test
    fun `a known address does nothing`() {
        address = byteArrayOf(1)
        assertEquals(RecoveryKeyProvisioner.Outcome.ALREADY_KNOWN, ensure())
        assertEquals(0, generated)
        assertTrue(uploads.isEmpty())
    }

    /** A key set on another device is not replaced, and no phrase is made for it. */
    @Test
    fun `a key set elsewhere is left alone`() {
        serverHasKey = true
        assertEquals(RecoveryKeyProvisioner.Outcome.SET_ELSEWHERE, ensure())
        assertEquals(0, generated)
    }

    /** Nothing is generated while the server cannot say whether a key exists: a phrase made then
     * could lose to the account's real key. */
    @Test
    fun `no phrase is made while the status is unknown`() {
        statusFails = true
        assertEquals(RecoveryKeyProvisioner.Outcome.DEFERRED, ensure())
        assertEquals(0, generated)
    }

    /** No screen lock: nothing can wait safely, so the visible setup runs (owner's decision).
     * Mutation: ignore `canHold` — a phrase is generated with nowhere safe to wait. */
    @Test
    fun `without a screen lock the visible setup runs`() {
        store.canHold = false
        assertEquals(RecoveryKeyProvisioner.Outcome.NEEDS_VISIBLE_SETUP, ensure())
        assertEquals(0, generated)
        assertTrue(uploads.isEmpty())
    }

    @Test
    fun `a phrase that cannot be stored is never uploaded`() {
        store.failStore = true
        assertEquals(RecoveryKeyProvisioner.Outcome.NEEDS_VISIBLE_SETUP, ensure())
        assertTrue(uploads.isEmpty())
    }

    /** Set but not moved behind authentication: the phrase stays pending — readable, not lost. */
    @Test
    fun `a failed promotion keeps the phrase pending`() {
        store.failPromote = true
        assertEquals(RecoveryKeyProvisioner.Outcome.PROVISIONED, ensure())
        assertEquals("phrase-1", store.pending?.first)
    }

    /** Android, beyond iOS: the next run moves a phrase left pending once its key is known.
     * Mutation: return on a known address before looking at the pending phrase. */
    @Test
    fun `a phrase left pending is moved on the next run`() {
        store.failPromote = true
        ensure()
        store.failPromote = false
        assertEquals(RecoveryKeyProvisioner.Outcome.ALREADY_KNOWN, ensure())
        assertEquals("phrase-1", store.held?.first)
        assertNull(store.pending)
    }

    /** A pending phrase whose upload was never answered, while the address came from the phrase
     * typed at the gate, names nothing: dropped, never offered as the copy. Mutation: promote
     * without comparing it with the address. */
    @Test
    fun `a pending phrase that is not the address's is dropped`() {
        store.pending = "orphan" to "u1"
        address = keyOf("the-real-one")
        assertEquals(RecoveryKeyProvisioner.Outcome.ALREADY_KNOWN, ensure())
        assertNull(store.pending)
        assertNull(store.held)
    }

    /** Another account's pending phrase is not this one's. */
    @Test
    fun `a pending phrase belongs to its account`() {
        store.pending = "someone-else" to "u2"
        assertEquals(RecoveryKeyProvisioner.Outcome.PROVISIONED, ensure())
        assertEquals(listOf("phrase-1"), uploads)
    }

    // The word check.

    @Test
    fun `the options hold the answer and only words from the phrase`() {
        val phrase = (1..12).map { "w$it" }
        val random = Random(7)
        phrase.indices.forEach { index ->
            val options = RecoveryViewModel.quizOptions(phrase, index, random)
            assertEquals(4, options.size)
            assertEquals("no word offered twice", 4, options.toSet().size)
            assertTrue(phrase[index] in options)
            assertTrue(phrase.containsAll(options))
        }
    }

    /** A phrase may repeat a word; the answer must still be offered once and the decoys differ. */
    @Test
    fun `a repeated word is not offered as its own decoy`() {
        val phrase = listOf("same", "same", "a", "b", "c", "d", "e", "f", "g", "h", "i", "j")
        val options = RecoveryViewModel.quizOptions(phrase, 0, Random(3))
        assertEquals(1, options.count { it == "same" })
        assertEquals(4, options.size)
    }
}
