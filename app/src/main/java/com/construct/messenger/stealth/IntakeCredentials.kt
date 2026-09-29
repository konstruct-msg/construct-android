package com.construct.messenger.stealth

import android.content.Context
import com.construct.messenger.diagnostics.Log
import com.construct.messenger.data.api.GrpcClient
import com.construct.messenger.data.local.KeystoreManager
import com.google.protobuf.ByteString
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import shared.proto.services.v1.MessagingServiceOuterClass.IntakeTagEntry
import shared.proto.services.v1.MessagingServiceOuterClass.PublishIntakeTagsRequest
import uniffi.construct_core.generateIntakeKey
import uniffi.construct_core.intakeEpoch
import uniffi.construct_core.intakeTag
import uniffi.construct_core.ppSealTokenBytes

/**
 * The credential a sealed envelope carries instead of a Privacy Pass token: a contact's intake
 * key, received in their card, turned into today's tag and sealed to the server — and our own,
 * handed out in our card, whose tags this device publishes so our contacts owe nothing.
 * `decisions/contact-traffic-is-vouched-not-purchased.md`. **Canon:** iOS
 * `IntakeCredentialService`.
 *
 * Our key is this device's: every device of the account mints and publishes its own, and the
 * server keeps them all as a set per epoch (construct-server `7fd850b`). One shared key would
 * need syncing between our devices; one per device needs nothing but the set.
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
    private val grpcClient: GrpcClient,
) {
    private val prefs = context.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)

    /** This device's key for our account, minted on first use — an install from before this
     * shipped and a fresh one are the same case, with no migration to find either. */
    fun ownKey(): ByteArray {
        keystoreManager.ownIntakeKey()?.takeIf { it.size == KEY_LENGTH }?.let { return it }
        val fresh = generateIntakeKey().toByteArray()
        keystoreManager.saveOwnIntakeKey(fresh)
        Log.i(TAG, "minted this device's intake key")
        return fresh
    }

    /**
     * Publish a window of our tags so envelopes from contacts holding our key owe no token. Once
     * per epoch per account; recorded only when the server took it, so a failed publish is retried
     * next start rather than costing a day. Silent on failure: contacts pay as before.
     */
    suspend fun publishIfNeeded(nowSeconds: Long = System.currentTimeMillis() / 1000) {
        val accountId = keystoreManager.getUserId()?.takeIf { it.isNotEmpty() } ?: return
        val epoch = intakeEpoch(nowSeconds.toULong())
        val lastKey = KEY_LAST_PUBLISHED_PREFIX + accountId.lowercase()
        val last = prefs.getLong(lastKey, -1L).takeIf { it >= 0 }?.toULong()
        if (!Publishing.shouldPublish(last, epoch)) return
        val key = ownKey()
        val entries = Publishing.window(epoch).mapNotNull { e ->
            runCatching { intakeTag(key.toUByteList(), accountId, e).toByteArray() }.getOrNull()?.let { tag ->
                IntakeTagEntry.newBuilder().setEpoch(e.toLong()).setTag(ByteString.copyFrom(tag)).build()
            }
        }
        if (entries.isEmpty()) return
        runCatching {
            grpcClient.messaging.publishIntakeTags(PublishIntakeTagsRequest.newBuilder().addAllTags(entries).build())
        }.onSuccess {
            prefs.edit().putLong(lastKey, epoch.toLong()).apply()
            Log.i(TAG, "published ${it.accepted}/${entries.size} intake tag(s) from epoch $epoch")
        }.onFailure { Log.i(TAG, "intake tag publish failed — contacts keep paying until it lands", it) }
    }

    /** Pure decisions about publishing, apart so a test can argue with them. Canon: iOS `IntakePublishing`. */
    internal object Publishing {
        /** Seven days ahead: a device offline for a week must not break its own incoming traffic. */
        const val WINDOW_EPOCHS = 7

        fun window(current: ULong): List<ULong> = (0 until WINDOW_EPOCHS).map { current + it.toULong() }

        /** Once per epoch; a clock that went backwards is not a reason to skip. */
        fun shouldPublish(last: ULong?, current: ULong): Boolean = last == null || last != current
    }

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
        const val KEY_LAST_PUBLISHED_PREFIX = "last_published:"
        /** `construct-core::intake::INTAKE_KEY_LEN`; the core does not export it. */
        const val KEY_LENGTH = 32
    }
}
