package com.construct.messenger.stealth

import android.content.Context
import com.construct.messenger.diagnostics.Log
import com.construct.messenger.data.api.GrpcClient
import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.domain.usecase.SendMessageUseCase
import com.construct.messenger.invite.ContactCardPayload
import com.google.protobuf.ByteString
import dagger.hilt.android.qualifiers.ApplicationContext
import io.grpc.Status
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.random.Random
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import shared.proto.services.v1.MessagingServiceOuterClass.GetIntakeStateRequest
import shared.proto.services.v1.MessagingServiceOuterClass.IntakeTagEntry
import shared.proto.services.v1.MessagingServiceOuterClass.IntakeVerdict
import shared.proto.services.v1.MessagingServiceOuterClass.PublishIntakeTagsRequest
import uniffi.construct_core.IntakeKeyState
import uniffi.construct_core.IntakeOffer
import uniffi.construct_core.intakeEpoch
import uniffi.construct_core.intakeMint
import uniffi.construct_core.intakeOffer
import uniffi.construct_core.intakeTag
import uniffi.construct_core.ppSealTokenBytes

/**
 * The credential a sealed envelope carries instead of a Privacy Pass token: a contact's intake
 * key, received in their card, turned into today's tag and sealed to the server — and our own,
 * handed out in our card, whose tags this device publishes so our contacts owe nothing.
 * `decisions/contact-traffic-is-vouched-not-purchased.md`. **Canon:** iOS
 * `IntakeCredentialService`.
 *
 * The key is the account's, with a generation (`decisions/intake-key-is-the-accounts-not-the-
 * devices.md`; construct-core `IntakeKeyState`): every device of the account holds the same one, a
 * device mints only when the account has no live key, and otherwise waits for it from one of our
 * own devices, as a card inside SENDER_SYNC. Until 2026-10-29 each device minted its own, and a
 * contact held whichever device's key reached it last — a gone or week-dark device's key stayed
 * unrecognised for good.
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
    /** Lazily: the send path reaches this class through [SealedSend]. */
    private val sendMessage: Provider<SendMessageUseCase>,
) {
    private val prefs = context.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)
    private val mintLock = Any()
    private val publishLock = Mutex()

    /**
     * The account's key as this device holds it, if it holds one of a generation (≥ 1): what a
     * contact card carries. No network, no minting; null while it is due from our own devices.
     */
    fun currentOwnKey(): IntakeKeyState? =
        (readOwn() as? OwnRead.Found)?.state?.takeIf { it.generation > 0u }

    /**
     * [currentOwnKey] once the server holds its tags: what a contact card may name. A card naming a
     * key the server does not hold has the contact's envelopes refused and the key suspended for
     * the epoch — after a rotation whose publish failed, the lazy card would do exactly that. The
     * eager cards after a rotation follow the same rule ([rotate] returns the key only once stored).
     */
    fun publishedOwnKey(): IntakeKeyState? {
        val own = currentOwnKey() ?: return null
        val accountId = keystoreManager.getUserId()?.takeIf { it.isNotEmpty() } ?: return null
        val last = runCatching { Published.decode(keystoreManager.intakePublished()) }.getOrNull()
        val epoch = intakeEpoch((System.currentTimeMillis() / 1000).toULong())
        return own.takeIf { Publishing.serverHolds(last, accountId, Published.keyId(it.key), epoch) }
    }

    /** What the keystore says about our key. An unreadable store is not an absent key. */
    private sealed interface OwnRead {
        data class Found(val state: IntakeKeyState) : OwnRead
        data object Absent : OwnRead
        data object Unreadable : OwnRead
    }

    private fun readOwn(): OwnRead = runCatching { keystoreManager.ownIntakeKey() }.fold(
        onSuccess = { stored -> stored?.let(KeyRecord::decode)?.let { OwnRead.Found(it) } ?: OwnRead.Absent },
        onFailure = {
            Log.w(TAG, "own intake key unreadable — not minting", it)
            OwnRead.Unreadable
        },
    )

    /**
     * The account's key, settled with the server when this device holds none of a generation:
     * promoted, minted, or null to wait for it from our own devices. Asked from publishing only,
     * so the server is asked at most as often as we publish. **Canon:** iOS `resolveOwnKey`.
     */
    private suspend fun resolveOwnKey(): IntakeKeyState? {
        val held = when (val read = readOwn()) {
            OwnRead.Unreadable -> return null
            OwnRead.Absent -> null
            is OwnRead.Found -> if (read.state.generation > 0u) return read.state else read.state
        }
        val server = try {
            grpcClient.messaging.getIntakeState(GetIntakeStateRequest.getDefaultInstance())
                .let { AccountIntake(it.generation.toUInt(), it.hasTagsForCurrentEpoch) }
        } catch (e: Exception) {
            AccountIntake.onError(e) ?: run {
                Log.i(TAG, "account intake state unavailable — asked again on the next publish", e)
                return null
            }
        }
        return when (val next = OwnKey.next(held, server.generation, server.hasLiveTags)) {
            is OwnKey.Next.Use -> next.state
            is OwnKey.Next.Promote -> adoptAsOwn(next.state, "promoted this device's key to generation 1")
            is OwnKey.Next.Mint -> runCatching { intakeMint(next.above) }
                .onFailure { Log.w(TAG, "intake key not minted", it) }
                .getOrNull()
                ?.let { adoptAsOwn(it, "minted generation ${it.generation} (account at ${server.generation})") }
            OwnKey.Next.Wait -> {
                Log.i(TAG, "the account's key (generation ${server.generation}) is due from our own devices — not publishing")
                null
            }
        }
    }

    internal data class AccountIntake(val generation: UInt, val hasLiveTags: Boolean) {
        companion object {
            /**
             * A server from before generations keeps none and ignores the one we publish: the
             * account is at 0 as far as it can tell. Waiting instead would stop every device on an
             * old key from publishing until the server caught up. Any other error: ask again.
             */
            fun onError(error: Throwable): AccountIntake? =
                if (Status.fromThrowable(error).code == Status.Code.UNIMPLEMENTED) AccountIntake(0u, false) else null
        }
    }

    /**
     * Makes [state] this device's key. Every copy of the previous one is stale, so the "sent to"
     * list and the publish marker go with it ([KeystoreManager.replaceOwnIntakeKey]).
     */
    private fun adoptAsOwn(state: IntakeKeyState, why: String): IntakeKeyState? = synchronized(mintLock) {
        runCatching { keystoreManager.replaceOwnIntakeKey(KeyRecord.encode(state)) }
            .onFailure {
                // Handing out a key nothing keeps would decide again on the next call.
                Log.w(TAG, "own intake key not saved — not used", it)
                return null
            }
        Log.i(TAG, why)
        state
    }

    /** One of our own devices handed us the account's key (a card off a session with it). */
    suspend fun receiveOwnKeyFromSibling(offered: IntakeKeyState) {
        val held = when (val read = readOwn()) {
            // Adopting would overwrite a key we cannot see; the sibling sends again on its next publish.
            OwnRead.Unreadable -> return
            OwnRead.Absent -> null
            is OwnRead.Found -> read.state
        }
        val offer = runCatching { intakeOffer(held, offered) }
            .onFailure { Log.w(TAG, "own device sent a malformed intake key — ignored", it) }
            .getOrNull() ?: return
        if (offer != IntakeOffer.ADOPT) return
        adoptAsOwn(offered, "took generation ${offered.generation} from our own device") ?: return
        publishIfNeeded()
    }

    /** Pure decisions about our own key, apart so a test can argue with them. **Canon:** iOS `IntakeOwnKey.next`. */
    internal object OwnKey {
        sealed interface Next {
            data class Use(val state: IntakeKeyState) : Next
            /** Our per-device key from before generations becomes the account's generation 1: the
             * contacts that hold it stay valid. Only while the account has no generation yet. */
            data class Promote(val state: IntakeKeyState) : Next
            /** No live key anywhere on the account: a new one above what the server last saw. */
            data class Mint(val above: UInt) : Next
            /** The account has a live key this device does not hold: it is due from our own
             * devices. Nothing is published meanwhile; our contacts pass on theirs. */
            data object Wait : Next
        }

        /** Each case is named by state — never by a key being late. */
        fun next(held: IntakeKeyState?, serverGeneration: UInt, serverHasLiveTags: Boolean): Next {
            if (held != null && held.generation > 0u) return Next.Use(held)
            val accountIsLive = serverGeneration > 0u && serverHasLiveTags
            if (held != null && serverGeneration == 0u) return Next.Promote(IntakeKeyState(1u, held.key))
            return if (accountIsLive) Next.Wait else Next.Mint(serverGeneration)
        }
    }

    /**
     * A key as the keystore keeps it: 36 bytes, the generation big-endian then the key. 32 bytes is
     * one written before 2026-10-29, with no generation: it reads as generation 0, so nothing
     * migrates. Anything else is damaged. **Canon:** iOS `IntakeKeyRecord`.
     */
    internal object KeyRecord {
        fun encode(state: IntakeKeyState): ByteArray {
            val g = state.generation.toInt()
            return byteArrayOf((g ushr 24).toByte(), (g ushr 16).toByte(), (g ushr 8).toByte(), g.toByte()) + state.key
        }

        fun decode(stored: ByteArray): IntakeKeyState? = when (stored.size) {
            KEY_LENGTH -> IntakeKeyState(0u, stored)
            KEY_LENGTH + 4 -> IntakeKeyState(
                stored.take(4).fold(0u) { acc, b -> (acc shl 8) or (b.toUInt() and 0xFFu) },
                stored.copyOfRange(4, stored.size),
            )
            else -> null
        }
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
        // A key of a generation is checked against the marker before anything else; one without
        // asks the server, every publish, until it resolves.
        currentOwnKey()?.let { held ->
            val due = Published(accountId, epoch, Published.keyId(held.key))
            if (!Publishing.shouldPublish(Published.decode(keystoreManager.intakePublished()), due)) return@withLock
        }
        val own = resolveOwnKey() ?: return@withLock
        val due = Published(accountId, epoch, Published.keyId(own.key))
        if (!Publishing.shouldPublish(Published.decode(keystoreManager.intakePublished()), due)) return@withLock
        publish(own, due)
    }

    /**
     * Stop vouching for a contact the user blocked or removed on this device: a new generation of
     * the account's key, published at once — a higher generation replaces the account's tags on
     * the server, so the old key in their hands stops delivering now rather than when its window
     * runs out — then handed to our own devices. Returns the key once the server holds it, so the
     * caller can hand it to the contacts that remain; null when it does not (they get it at their
     * next exchange: the "sent to" list went with the old key).
     *
     * A block learnt from another of our devices does not come here: that device rotated. An
     * unblock does not either. **Canon:** iOS, per construct-messenger's point-4 rules
     * (`decisions/intake-key-is-the-accounts-not-the-devices.md`).
     */
    suspend fun rotate(reason: String, nowSeconds: Long = System.currentTimeMillis() / 1000): IntakeKeyState? = publishLock.withLock {
        val accountId = keystoreManager.getUserId()?.takeIf { it.isNotEmpty() } ?: return@withLock null
        val held = when (val read = readOwn()) {
            // Replacing a key we cannot see could overwrite a newer one; the next exchange decides.
            OwnRead.Unreadable -> return@withLock null
            OwnRead.Absent -> null
            is OwnRead.Found -> read.state
        }
        // Unreachable: above our own, and the publish names the account's if a sibling is higher.
        val server = runCatching { grpcClient.messaging.getIntakeState(GetIntakeStateRequest.getDefaultInstance()).generation.toUInt() }
            .getOrElse { AccountIntake.onError(it)?.generation ?: 0u }
        val epoch = intakeEpoch(nowSeconds.toULong())
        var above = Rotation.above(held?.generation, server)
        // Two passes at most: [Rotation.reMintAbove] gives the second one its generation.
        repeat(2) { pass ->
            val fresh = runCatching { intakeMint(above) }
                .onFailure { Log.w(TAG, "intake key not minted for a rotation", it) }
                .getOrNull()
                ?.let { adoptAsOwn(it, "rotated to generation ${it.generation} — $reason") }
                ?: return@withLock null
            val outcome = publish(fresh, Published(accountId, epoch, Published.keyId(fresh.key)))
            if (outcome is PublishOutcome.Stored) return@withLock fresh
            above = Rotation.reMintAbove(outcome, alreadyReMinted = pass > 0) ?: return@withLock null
        }
        null
    }

    /** Pure decisions about a rotation, apart so a test can argue with them. */
    internal object Rotation {
        /** Above every generation this account is known to have used: ours, and the server's. */
        fun above(held: UInt?, server: UInt): UInt = maxOf(held ?: 0u, server)

        /**
         * A sibling rotated meanwhile and the server holds its key: once more, above the
         * generation it named. Once — that generation is the address, not a retry budget. Any
         * other outcome ends it: a failed publish is retried by the next ordinary one.
         */
        fun reMintAbove(outcome: PublishOutcome, alreadyReMinted: Boolean): UInt? =
            (outcome as? PublishOutcome.Stale)?.current?.takeIf { !alreadyReMinted }

        /** An hour, in which the cards to the remaining contacts are spread. */
        const val SPREAD_MS = 3_600_000L

        /**
         * When each remaining contact is handed the new key: one envelope each, at an independent
         * random moment within [SPREAD_MS] — a batch would show the server the moment and the size
         * of our contact list. [dropped] (the one blocked or removed), blocked contacts and our
         * own account get none.
         */
        fun cardSchedule(
            contacts: Collection<String>,
            dropped: String,
            ownAccount: String?,
            random: Random,
        ): List<Pair<String, Long>> =
            contacts.filterNot { it.equals(dropped, ignoreCase = true) || it.equals(ownAccount, ignoreCase = true) }
                .distinctBy { it.lowercase() }
                .map { it to random.nextLong(SPREAD_MS) }
    }

    /** What the server did with a publish. */
    internal sealed interface PublishOutcome {
        data object Stored : PublishOutcome
        /** The account is at [current], above ours: nothing was stored. */
        data class Stale(val current: UInt) : PublishOutcome
        data object Failed : PublishOutcome
    }

    /** Publishes [own]'s window; on success marks [due] and hands the key to our own devices. Under [publishLock]. */
    private suspend fun publish(own: IntakeKeyState, due: Published): PublishOutcome {
        val epoch = due.epoch
        val accountId = due.accountId
        val entries = Publishing.window(epoch).mapNotNull { e ->
            runCatching { intakeTag(own.key, accountId, e) }.getOrNull()?.let { tag ->
                IntakeTagEntry.newBuilder().setEpoch(e.toLong()).setTag(ByteString.copyFrom(tag)).build()
            }
        }
        if (entries.isEmpty()) return PublishOutcome.Failed
        val response = runCatching {
            grpcClient.messaging.publishIntakeTags(
                PublishIntakeTagsRequest.newBuilder().addAllTags(entries).setGeneration(own.generation.toInt()).build(),
            )
        }.onFailure { Log.i(TAG, "intake tag publish failed — contacts keep paying until it lands", it) }
            .getOrNull() ?: return PublishOutcome.Failed
        val current = response.currentGeneration.toUInt()
        if (Publishing.refusedAsStale(current, own.generation)) {
            // The account moved on; the newer key is due from one of our own devices. Not marked,
            // so the next publish asks again.
            Log.i(TAG, "publish of generation ${own.generation} refused — the account is at $current")
            return PublishOutcome.Stale(current)
        }
        keystoreManager.saveIntakePublished(due.encoded())
        Log.i(TAG, "published ${response.accepted}/${entries.size} intake tag(s), generation ${own.generation}, from epoch $epoch")
        offerOwnKeyToOwnDevices(own)
        return PublishOutcome.Stored
    }

    /**
     * The account's key to our own devices that do not have it: one linked since, or all of them
     * after a change (which cleared the list). After a publish, so a device is never handed a key
     * whose tags the server does not hold.
     */
    private suspend fun offerOwnKeyToOwnDevices(own: IntakeKeyState) {
        val card = ContactCardPayload(intakeKey = own.key, intakeGeneration = own.generation).encoded()
        val reached = runCatching { sendMessage.get().sendOwnContactCard(card, keystoreManager.contactCardSentTo()) }
            .onFailure { Log.i(TAG, "own devices not handed the key — the next publish tries again", it) }
            .getOrNull().orEmpty()
        reached.forEach(keystoreManager::markContactCardSent)
        if (reached.isNotEmpty()) Log.i(TAG, "generation ${own.generation} handed to ${reached.size} own device(s)")
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

        /**
         * The last publish the server took was this key's, for this account, and its window still
         * covers [currentEpoch]: a publish runs [WINDOW_EPOCHS] ahead, so one from an earlier epoch
         * still holds today's tag — and one a week old does not. A marker from the future (a clock
         * set back) is not trusted. **Canon:** iOS `IntakePublishing.serverHolds`.
         */
        fun serverHolds(last: Published?, accountId: String, keyId: String, currentEpoch: ULong): Boolean =
            last != null && last.keyId == keyId && last.accountId.equals(accountId, ignoreCase = true) &&
                currentEpoch >= last.epoch && (currentEpoch - last.epoch).toLong() < WINDOW_EPOCHS

        /** The account's generation above ours: nothing was stored. An old server answers 0. */
        fun refusedAsStale(currentGeneration: UInt, ours: UInt): Boolean = currentGeneration > ours
    }

    /**
     * A contact's card named the key their account accepts. Kept only when its generation is above
     * the one held (`intakeOffer`): a late card, or an old build's per-device key, does not replace
     * the account's current one.
     */
    fun recordPeerKey(accountId: String, offered: IntakeKeyState) {
        val held = keystoreManager.peerIntakeKey(accountId)?.let(KeyRecord::decode)
        val offer = runCatching { intakeOffer(held, offered) }
            .onFailure { Log.w(TAG, "peer ${accountId.take(8)}… sent a ${offered.key.size}-byte intake key — ignored") }
            .getOrNull() ?: return
        if (offer != IntakeOffer.ADOPT) {
            Log.d(TAG, "kept generation ${held?.generation ?: 0u} from ${accountId.take(8)}… over ${offered.generation}")
            return
        }
        keystoreManager.savePeerIntakeKey(accountId, KeyRecord.encode(offered))
        Log.i(TAG, "stored intake key generation ${offered.generation} from ${accountId.take(8)}…")
    }

    /**
     * The sealed tag for an envelope to [accountId], or null — and null is not a failure: the
     * envelope pays a token, as every envelope did before this existed.
     */
    fun sealedTag(accountId: String, nowSeconds: Long = System.currentTimeMillis() / 1000): ByteArray? {
        val epoch = intakeEpoch(nowSeconds.toULong())
        if (isRejected(accountId, epoch.toLong())) return null
        val key = keystoreManager.peerIntakeKey(accountId)?.let(KeyRecord::decode)?.key ?: return null
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
