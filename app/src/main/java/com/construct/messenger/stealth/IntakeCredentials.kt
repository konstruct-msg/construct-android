package com.construct.messenger.stealth

import android.content.Context
import com.construct.messenger.diagnostics.Log
import com.construct.messenger.data.api.GrpcClient
import com.construct.messenger.data.local.KeystoreManager
import com.google.protobuf.ByteString
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import shared.proto.services.v1.MessagingServiceOuterClass.IntakeTagEntry
import shared.proto.services.v1.MessagingServiceOuterClass.IntakeVerdict
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
    private val mintLock = Any()
    private val publishLock = Mutex()

    /**
     * This device's key for our account, minted on first use — an install from before this
     * shipped and a fresh one are the same case, with no migration to find either. Minted once
     * under a lock: on a first start the card and the publish ask together, and two mints would
     * hand contacts a key whose tags were never published.
     */
    fun ownKey(): ByteArray = synchronized(mintLock) {
        keystoreManager.ownIntakeKey()?.takeIf { it.size == KEY_LENGTH }?.let { return it }
        val fresh = generateIntakeKey()
        keystoreManager.replaceOwnIntakeKey(fresh)
        Log.i(TAG, "minted this device's intake key — cards owed again, tags due now")
        fresh
    }

    /**
     * Publish a window of our tags so envelopes from contacts holding our key owe no token. Once
     * per epoch for this account and this key; recorded only when the server took it, so a failed
     * publish is retried at the next call rather than costing a day. Called at start, on every
     * stream connect and hourly while the process lives — the foreground service keeps it alive
     * past the window's end. Silent on failure: contacts pay as before.
     */
    suspend fun publishIfNeeded(nowSeconds: Long = System.currentTimeMillis() / 1000) = publishLock.withLock {
        val accountId = keystoreManager.getUserId()?.takeIf { it.isNotEmpty() } ?: return@withLock
        val epoch = intakeEpoch(nowSeconds.toULong())
        val key = ownKey()
        val due = Published(accountId, epoch, Published.keyId(key))
        if (!Publishing.shouldPublish(Published.decode(keystoreManager.intakePublished()), due)) return@withLock
        val entries = Publishing.window(epoch).mapNotNull { e ->
            runCatching { intakeTag(key, accountId, e) }.getOrNull()?.let { tag ->
                IntakeTagEntry.newBuilder().setEpoch(e.toLong()).setTag(ByteString.copyFrom(tag)).build()
            }
        }
        if (entries.isEmpty()) return@withLock
        runCatching {
            grpcClient.messaging.publishIntakeTags(PublishIntakeTagsRequest.newBuilder().addAllTags(entries).build())
        }.onSuccess {
            keystoreManager.saveIntakePublished(due.encoded())
            Log.i(TAG, "published ${it.accepted}/${entries.size} intake tag(s) from epoch $epoch")
        }.onFailure { Log.i(TAG, "intake tag publish failed — contacts keep paying until it lands", it) }
    }

    /**
     * What a publish covered: the account, the epoch and the key. A marker naming only the epoch
     * outlived a re-minted key, and the new key's tags waited for the next epoch.
     */
    internal data class Published(val accountId: String, val epoch: ULong, val keyId: String) {
        fun encoded(): String = "${accountId.lowercase()}|$epoch|$keyId"

        companion object {
            fun decode(stored: String?): Published? {
                val parts = stored?.split('|')?.takeIf { it.size == 3 } ?: return null
                val epoch = parts[1].toULongOrNull() ?: return null
                return Published(parts[0], epoch, parts[2])
            }

            /** Names the key without storing it twice. */
            fun keyId(key: ByteArray): String =
                MessageDigest.getInstance("SHA-256").digest(key).take(8).joinToString("") { "%02x".format(it) }
        }
    }

    /** Pure decisions about publishing, apart so a test can argue with them. Canon: iOS `IntakePublishing`. */
    internal object Publishing {
        /** Seven days ahead: a device offline for a week must not break its own incoming traffic. */
        const val WINDOW_EPOCHS = 7

        fun window(current: ULong): List<ULong> = (0 until WINDOW_EPOCHS).map { current + it.toULong() }

        /**
         * Once per epoch per account and key: a new key, or another account, publishes at once. A
         * clock that went backwards is not a reason to skip.
         */
        fun shouldPublish(last: Published?, due: Published): Boolean =
            last == null || last.epoch != due.epoch || last.keyId != due.keyId ||
                !last.accountId.equals(due.accountId, ignoreCase = true)
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
        val tag = runCatching { intakeTag(key, accountId, epoch) }
            .onFailure { Log.w(TAG, "intake tag for ${accountId.take(8)}… not derived", it) }
            .getOrNull() ?: return null
        // Sealed or not at all: SealedInner is plaintext to the relay, and a tag in the clear is
        // free sending to this contact for whoever reads it, until the epoch rolls. Unlike a token
        // there is no plaintext fallback.
        val serverKey = serverKeys.tokenEncryptionKey() ?: return null
        return runCatching { ppSealTokenBytes(tag, serverKey) }
            .onFailure { Log.w(TAG, "intake tag seal failed", it) }
            .getOrNull()
    }

    /**
     * What the server said of the tag on an envelope to [accountId]. Only "not accepted" acts,
     * and it suspends rather than drops: the commonest cause is a device of theirs whose window
     * lapsed while it was off, and that key works again once it publishes — a dropped key would
     * never come back, since they have no reason to send the card again. Under warn this is the
     * only way a stale key is noticed; until 2026-10-29 it was presented forever.
     */
    fun onVerdict(accountId: String, verdict: IntakeVerdict) {
        if (!Verdict.suspendsKey(verdict)) return
        noteRejected(accountId)
        Log.i(TAG, "intake tag for ${accountId.take(8)}… not accepted — paying until the epoch rolls")
    }

    /** Pure decision about the server's verdict, apart so a test can argue with it. */
    internal object Verdict {
        /**
         * "Unchecked" is the server's fault (its store, its key, or a tag sealed to a key it no
         * longer has), not the key's; "none", "accepted" and an old server say nothing against it.
         */
        fun suspendsKey(verdict: IntakeVerdict): Boolean = verdict == IntakeVerdict.INTAKE_VERDICT_NOT_ACCEPTED
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
