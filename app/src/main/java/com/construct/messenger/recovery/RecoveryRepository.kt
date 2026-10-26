package com.construct.messenger.recovery

import com.construct.messenger.crypto.CryptoManager
import com.construct.messenger.data.api.GrpcClient
import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.invite.AccountAddress
import com.construct.messenger.diagnostics.Log
import com.google.protobuf.ByteString
import io.grpc.Status
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import shared.proto.services.v1.AuthServiceOuterClass.GetRecoveryStatusRequest
import shared.proto.services.v1.AuthServiceOuterClass.SetRecoveryKeyRequest

/**
 * What the server says about this account's recovery key. [isHybrid]: the key is Ed25519 +
 * ML-DSA-65 (todo 222); an Ed25519-only one is upgraded by the next client holding the phrase.
 */
data class RecoveryStatus(val isSetup: Boolean, val fingerprint: String?, val isHybrid: Boolean = false)

/**
 * The account's key is still Ed25519 only and no phrase waits here for its copy (the copy
 * upgrades it): the person is asked to enter the phrase once. **Canon:** iOS `upgradeNeeded`.
 */
fun upgradeNeeded(status: RecoveryStatus?, held: HeldPhrase): Boolean =
    status != null && status.isSetup && !status.isHybrid && held != HeldPhrase.HELD

/** Why a phrase was not accepted on this device. */
enum class ConfirmFailure { INVALID_PHRASE, NOT_SET_UP, OTHER_ACCOUNT }

/** How setting up a phrase ended. */
enum class SetUpOutcome {
    /** The account's key is this phrase's; the device keeps the address. */
    DONE,
    /** The account already has a key from another phrase — set by an earlier attempt. */
    OTHER_PHRASE_SET,
    /** Nothing is known to have been set: the connection, or the server, failed. */
    FAILED,
}

/**
 * What a failed `SetRecoveryKey` actually left on the server, read from the status asked
 * afterwards. A key is set once and never changed (identity-service), so a call whose answer was
 * lost — a dropped connection, a route switch — may still have set it: a retry then meets
 * ALREADY_EXISTS. Shown as "check the connection", that sent testers round in circles, and a new
 * phrase never fitted while the first attempt's did (2026-10-03). [status] null: not even the
 * status could be read.
 */
internal fun afterFailedSetUp(status: RecoveryStatus?, publicKey: ByteArray): SetUpOutcome {
    val fingerprint = status?.fingerprint
    if (status == null || !status.isSetup || fingerprint == null) return SetUpOutcome.FAILED
    return if (AccountAddress.matchesServerFingerprint(publicKey, fingerprint)) SetUpOutcome.DONE else SetUpOutcome.OTHER_PHRASE_SET
}

/**
 * The recovery phrase: set it up for the account, or teach this device the account's address
 * from it. Every path that has the phrase in hand leaves [KeystoreManager] holding the address —
 * the recovery public key — because the phrase is the only source of it this app trusts.
 *
 * **Canon:** iOS `AccountRecoveryViewModel` (setup, confirm).
 */
@Singleton
class RecoveryRepository @Inject constructor(
    private val grpcClient: GrpcClient,
    private val cryptoManager: CryptoManager,
    private val keystoreManager: KeystoreManager,
    private val vault: RecoveryPhraseVault,
) {
    fun hasOwnAddress(): Boolean = keystoreManager.getOwnAccountAddress() != null

    fun ownAddress(): ByteArray? = keystoreManager.getOwnAccountAddress()

    /** A silently made phrase still waits here for its copy — or was lost before it was made. */
    fun copyOwed(): Boolean = keystoreManager.getUserId()?.let { vault.held(it) != HeldPhrase.NONE } == true

    /** The account's address [phrase] determines: the hybrid recovery key's Ed25519 half. */
    fun publicKeyOf(phrase: String): ByteArray = addressOf(cryptoManager.recoveryPublicKey(phrase))

    fun newPhrase(): List<String> = cryptoManager.generateMnemonic(PHRASE_WORDS).split(" ")

    suspend fun status(): RecoveryStatus {
        // Without a deadline a stalled call left the row blank with no error, indefinitely.
        val response = grpcClient.auth.withDeadlineAfter(STATUS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .getRecoveryStatus(GetRecoveryStatusRequest.getDefaultInstance())
        return RecoveryStatus(
            isSetup = response.isSetup,
            fingerprint = if (response.hasFingerprint()) response.fingerprint else null,
            isHybrid = response.isHybrid,
        )
    }

    /**
     * Registers [words] as the account's recovery key and keeps the address it gives. A failure is
     * checked against what the server now holds ([afterFailedSetUp]) before it is reported.
     */
    suspend fun setUp(words: List<String>): SetUpOutcome {
        val userId = keystoreManager.getUserId() ?: error("not authenticated")
        val (outcome, publicKey) = send(words.joinToString(" "), userId)
        if (outcome == SetUpOutcome.DONE) keystoreManager.saveOwnAccountAddress(publicKey)
        return outcome
    }

    /**
     * Signs and sends `SetRecoveryKey` for [phrase] with the hybrid key; the outcome and the
     * address. Sent to an account whose Ed25519 key is the address, it is the upgrade (todo 222).
     * Keeps nothing: the silent path ([RecoveryKeyProvisioner]) stores the address itself, after
     * the vault.
     */
    suspend fun send(phrase: String, userId: String): Pair<SetUpOutcome, ByteArray> {
        val timestamp = System.currentTimeMillis() / 1000
        // The core builds the signed bytes (`construct_server_trust::recovery::setup_signable`),
        // the same ones the server checks.
        val signed = cryptoManager.signRecoverySetup(phrase, userId, timestamp)
        val publicKey = addressOf(signed.publicKey)
        val failure = try {
            grpcClient.auth.withDeadlineAfter(STATUS_TIMEOUT_SECONDS, TimeUnit.SECONDS).setRecoveryKey(
                SetRecoveryKeyRequest.newBuilder()
                    .setRecoveryPublicKey(ByteString.copyFrom(signed.publicKey))
                    .setSetupSignature(ByteString.copyFrom(signed.signature))
                    .setTimestamp(timestamp)
                    .build(),
            )
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e
        }
        val outcome = if (failure == null) {
            SetUpOutcome.DONE
        } else {
            // The code and the server's words, never the phrase or a key.
            val status = Status.fromThrowable(failure)
            Log.w(TAG, "recovery setup refused: ${status.code} — ${status.description}")
            afterFailedSetUp(runCatching { status() }.getOrNull(), publicKey)
        }
        return outcome to publicKey
    }

    fun saveOwnAddress(publicKey: ByteArray) = keystoreManager.saveOwnAccountAddress(publicKey)

    /**
     * With [phrase] in hand: an account still on its Ed25519 key gets the hybrid one (todo 222).
     * Only for the account's own phrase — the fingerprint is checked first — and quietly: a
     * failure leaves the account as it was, and the next time the phrase is at hand tries again.
     * After 2026-11-01 the server takes no Ed25519 claim, but a hybrid recovery upgrades the
     * account by itself, so a missed upgrade locks nobody out.
     */
    suspend fun upgradeIfEd25519Only(phrase: String, known: RecoveryStatus? = null) {
        val userId = keystoreManager.getUserId() ?: return
        val status = known ?: runCatching { status() }.getOrNull() ?: return
        val fingerprint = status.fingerprint
        if (!status.isSetup || status.isHybrid || fingerprint == null) return
        if (!AccountAddress.matchesServerFingerprint(publicKeyOf(phrase), fingerprint)) return
        val (outcome, _) = send(phrase, userId)
        Log.i(TAG, "recovery key upgrade to hybrid: $outcome")
    }

    fun userId(): String? = keystoreManager.getUserId()

    /**
     * Learns the account's address from [phrase], checked against the fingerprint the server
     * holds. The phrase never leaves the device; the server can only make the check fail.
     * Returns `null` on success.
     */
    suspend fun confirm(phrase: String): ConfirmFailure? {
        val normalized = phrase.trim().lowercase().split(Regex("\\s+")).joinToString(" ")
        if (!cryptoManager.isValidMnemonic(normalized)) return ConfirmFailure.INVALID_PHRASE
        val status = status()
        val fingerprint = status.fingerprint
        if (!status.isSetup || fingerprint == null) return ConfirmFailure.NOT_SET_UP
        val publicKey = publicKeyOf(normalized)
        if (!AccountAddress.matchesServerFingerprint(publicKey, fingerprint)) {
            return ConfirmFailure.OTHER_ACCOUNT
        }
        keystoreManager.saveOwnAccountAddress(publicKey)
        runCatching { upgradeIfEd25519Only(normalized, status) }
            .onFailure { if (it is CancellationException) throw it }
        return null
    }

    companion object {
        const val PHRASE_WORDS = 12

        /** The Ed25519 half's length, which leads the hybrid key. */
        private const val ED25519_PUBLIC_KEY_BYTES = 32

        /** The address is the hybrid recovery key's Ed25519 half, the key accounts had before. */
        fun addressOf(hybridPublicKey: ByteArray): ByteArray = hybridPublicKey.copyOfRange(0, ED25519_PUBLIC_KEY_BYTES)
        private const val TAG = "Recovery"
    }
}

private const val STATUS_TIMEOUT_SECONDS = 20L
