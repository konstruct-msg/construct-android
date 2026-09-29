package com.construct.messenger.stealth

import android.content.Context
import android.util.Log
import com.construct.messenger.data.local.KeystoreManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import uniffi.construct_core.intakeEpoch
import uniffi.construct_core.intakeTag
import uniffi.construct_core.ppSealTokenBytes

/**
 * The credential a sealed envelope carries instead of a Privacy Pass token: a contact's intake
 * key, received in their card, turned into today's tag and sealed to the server.
 * `decisions/contact-traffic-is-vouched-not-purchased.md`. **Canon:** iOS
 * `IntakeCredentialService` (the peer half; this device does not mint a key of its own yet).
 *
 * The derivation is the core's (`intakeTag`), including the account-id normalisation: two
 * clients disagreeing about case would produce different tags, and the envelope would be charged
 * with nothing to say why.
 */
@Singleton
class IntakeCredentials @Inject constructor(
    @ApplicationContext context: Context,
    private val keystoreManager: KeystoreManager,
    private val serverKeys: ServerKeysProvider,
) {
    private val prefs = context.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)

    /** A contact's card named the key their account accepts. */
    fun recordPeerKey(accountId: String, key: ByteArray) {
        if (key.size != KEY_LENGTH) {
            Log.w(TAG, "peer ${accountId.take(8)}… sent a ${key.size}-byte intake key — ignored")
            return
        }
        keystoreManager.savePeerIntakeKey(accountId, key)
        Log.i(TAG, "stored intake key from ${accountId.take(8)}…")
    }

    /**
     * The sealed tag for an envelope to [accountId], or null — and null is not a failure: the
     * envelope pays a token, as every envelope did before this existed.
     */
    fun sealedTag(accountId: String, nowSeconds: Long = System.currentTimeMillis() / 1000): ByteArray? {
        val epoch = intakeEpoch(nowSeconds.toULong())
        if (isRejected(accountId, epoch.toLong())) return null
        val key = keystoreManager.peerIntakeKey(accountId)?.takeIf { it.size == KEY_LENGTH } ?: return null
        val tag = runCatching { intakeTag(key.toUByteList(), accountId, epoch).toByteArray() }
            .onFailure { Log.w(TAG, "intake tag for ${accountId.take(8)}… not derived", it) }
            .getOrNull() ?: return null
        // Sealed or not at all: SealedInner is plaintext to the relay, and a tag in the clear is
        // free sending to this contact for whoever reads it, until the epoch rolls. Unlike a token
        // there is no plaintext fallback.
        val serverKey = serverKeys.tokenEncryptionKey() ?: return null
        return runCatching { ppSealTokenBytes(tag.toUByteList(), serverKey.toUByteList()).toByteArray() }
            .onFailure { Log.w(TAG, "intake tag seal failed", it) }
            .getOrNull()
    }

    /**
     * The server refused an envelope that carried this contact's credential: had it honoured it,
     * it would not have looked at tokens. Stop offering it until the epoch rolls — their published
     * window has a hole or our copy of their key is stale, and neither is fixed by asking again.
     */
    fun noteRejected(accountId: String, nowSeconds: Long = System.currentTimeMillis() / 1000) {
        val epoch = intakeEpoch(nowSeconds.toULong()).toLong()
        prefs.edit().putLong(KEY_REJECTED_PREFIX + accountId.lowercase(), epoch).apply()
    }

    private fun isRejected(accountId: String, epoch: Long): Boolean =
        prefs.getLong(KEY_REJECTED_PREFIX + accountId.lowercase(), -1L) == epoch

    private companion object {
        const val TAG = "Intake"
        const val PREFS_FILE_NAME = "intake_prefs"
        const val KEY_REJECTED_PREFIX = "rejected:"
        /** `construct-core::intake::INTAKE_KEY_LEN`; the core does not export it. */
        const val KEY_LENGTH = 32
    }
}
