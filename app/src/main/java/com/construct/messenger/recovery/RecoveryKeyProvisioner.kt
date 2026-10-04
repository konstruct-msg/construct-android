package com.construct.messenger.recovery

import com.construct.messenger.diagnostics.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Makes the account's recovery key without a screen. **Canon:** iOS `RecoveryKeyProvisioner`;
 * `decisions/recovery-key-backup-is-deferred-not-skipped.md`, slice S1.
 *
 * The key is the account's address, and until 2026-10-04 it existed only once the person had sat
 * through twelve words and a quiz — so the first thing a new account met when it reached for an
 * invite was that wall.
 *
 * Order is the point: the phrase is in the vault before the server hears of the key, so there is
 * never a key on the server whose phrase exists nowhere. A retry uses the stored phrase, not a new
 * one; the server accepts the same key twice (construct-server `e53c0ee`), and before that the
 * fingerprint check in [afterFailedSetUp] told our own key from another.
 */
@Singleton
class RecoveryKeyProvisioner(private val deps: Dependencies) {

    enum class Outcome {
        /** This device knows the address already; nothing to do. */
        ALREADY_KNOWN,
        /** The account has a key this device has not seen (set up elsewhere, or another device
         * of the account won a race). The gate's confirm path covers it. */
        SET_ELSEWHERE,
        /** Created and accepted; the phrase waits in the vault for its copy. */
        PROVISIONED,
        /** No screen lock, so nothing can wait safely: the visible setup runs now. */
        NEEDS_VISIBLE_SETUP,
        /** The server could not be reached or failed. A pending phrase, if one was made, stays
         * for the next attempt. */
        DEFERRED,
    }

    interface Dependencies {
        /** The account's address as this device knows it, or null. */
        fun knownAddress(): ByteArray?
        fun publicKeyOf(phrase: String): ByteArray
        /** Whether the server holds a key for the account. Throws when it cannot be asked. */
        suspend fun serverHasKey(): Boolean
        fun generatePhrase(): String
        /** Derives, signs and sends `SetRecoveryKey`: what came of it and the public key. */
        suspend fun upload(phrase: String, userId: String): Pair<SetUpOutcome, ByteArray>
        fun rememberAddress(publicKey: ByteArray)
        val store: RecoveryPhraseStore
    }

    @Inject constructor(repository: RecoveryRepository, vault: RecoveryPhraseVault) : this(
        object : Dependencies {
            override fun knownAddress() = repository.ownAddress()
            override fun publicKeyOf(phrase: String) = repository.publicKeyOf(phrase)
            override suspend fun serverHasKey() = repository.status().isSetup
            override fun generatePhrase() = repository.newPhrase().joinToString(" ")
            override suspend fun upload(phrase: String, userId: String) = repository.send(phrase, userId)
            override fun rememberAddress(publicKey: ByteArray) = repository.saveOwnAddress(publicKey)
            override val store: RecoveryPhraseStore = vault
        },
    )

    private val mutex = Mutex()

    /** Idempotent and one at a time: a launch and the end of onboarding may both ask. */
    suspend fun ensureKey(userId: String): Outcome = mutex.withLock { run(userId) }

    private suspend fun run(userId: String): Outcome {
        val store = deps.store
        deps.knownAddress()?.let { address ->
            // A phrase left pending after its key was set (the move behind authentication failed
            // last time) is moved now — if it is this address's. One whose upload was never
            // answered while the address came from elsewhere (the phrase typed at the gate) names
            // nothing, and is dropped rather than offered as a copy.
            store.pendingPhrase(userId)?.let { pending ->
                if (deps.publicKeyOf(pending).contentEquals(address)) {
                    if (store.promotePending(userId)) Log.i(TAG, "pending phrase moved behind authentication")
                } else {
                    store.forgetPending()
                    Log.i(TAG, "pending phrase is not the account's — dropped")
                }
            }
            return Outcome.ALREADY_KNOWN
        }

        // A phrase made on an earlier launch whose upload never got an answer goes first, before
        // anything is asked: the server may already hold its key.
        val phrase = store.pendingPhrase(userId) ?: run {
            val hasKey = try {
                deps.serverHasKey()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.i(TAG, "status unavailable (${e.javaClass.simpleName}) — later")
                return Outcome.DEFERRED
            }
            if (hasKey) return Outcome.SET_ELSEWHERE
            if (!store.canHold) return Outcome.NEEDS_VISIBLE_SETUP
            val made = try {
                deps.generatePhrase()
            } catch (e: Exception) {
                Log.w(TAG, "phrase generation failed: ${e.javaClass.simpleName}")
                return Outcome.DEFERRED
            }
            if (!store.storePending(made, userId)) {
                Log.w(TAG, "could not store the pending phrase — visible setup")
                return Outcome.NEEDS_VISIBLE_SETUP
            }
            made
        }

        val (outcome, publicKey) = try {
            deps.upload(phrase, userId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SetUpOutcome.FAILED to ByteArray(0)
        }
        when (outcome) {
            SetUpOutcome.FAILED -> {
                Log.i(TAG, "upload deferred")
                return Outcome.DEFERRED
            }
            SetUpOutcome.OTHER_PHRASE_SET -> {
                // Not ours, and never will be: the phrase names nothing.
                store.forgetPending()
                Log.i(TAG, "another key is set for the account — pending phrase dropped")
                return Outcome.SET_ELSEWHERE
            }
            SetUpOutcome.DONE -> Unit
        }
        deps.rememberAddress(publicKey)
        if (!store.promotePending(userId)) {
            // Set, address known, but the phrase could not move behind authentication. It stays
            // pending — readable, never lost — and the next run retries the move.
            Log.w(TAG, "set, but the phrase could not be held — left pending")
        }
        Log.i(TAG, "created and set; copy pending")
        return Outcome.PROVISIONED
    }

    private companion object {
        const val TAG = "RecoveryKey"
    }
}
