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

/** What the server says about this account's recovery key. */
data class RecoveryStatus(val isSetup: Boolean, val fingerprint: String?)

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

    fun publicKeyOf(phrase: String): ByteArray = cryptoManager.deriveRecoveryKeypair(phrase).publicKey

    fun newPhrase(): List<String> = cryptoManager.generateMnemonic(PHRASE_WORDS).split(" ")

    suspend fun status(): RecoveryStatus {
        // Without a deadline a stalled call left the row blank with no error, indefinitely.
        val response = grpcClient.auth.withDeadlineAfter(STATUS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .getRecoveryStatus(GetRecoveryStatusRequest.getDefaultInstance())
        return RecoveryStatus(
            isSetup = response.isSetup,
            fingerprint = if (response.hasFingerprint()) response.fingerprint else null,
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
     * Signs and sends `SetRecoveryKey` for [phrase]; the outcome and the public key. Keeps nothing:
     * the silent path ([RecoveryKeyProvisioner]) stores the address itself, after the vault.
     */
    suspend fun send(phrase: String, userId: String): Pair<SetUpOutcome, ByteArray> {
        val keypair = cryptoManager.deriveRecoveryKeypair(phrase)
        val timestamp = System.currentTimeMillis() / 1000
        // The message iOS signs, byte for byte; identity-service checks it.
        val signature = cryptoManager.signWithRecoveryKey(
            keypair,
            "CONSTRUCT_RECOVERY_SETUP:$userId:$timestamp",
        )
        val publicKey = keypair.publicKey
        val failure = try {
            grpcClient.auth.withDeadlineAfter(STATUS_TIMEOUT_SECONDS, TimeUnit.SECONDS).setRecoveryKey(
                SetRecoveryKeyRequest.newBuilder()
                    .setRecoveryPublicKey(ByteString.copyFrom(publicKey))
                    .setSetupSignature(ByteString.copyFrom(signature))
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
        val publicKey = cryptoManager.deriveRecoveryKeypair(normalized)
            .publicKey
        if (!AccountAddress.matchesServerFingerprint(publicKey, fingerprint)) {
            return ConfirmFailure.OTHER_ACCOUNT
        }
        keystoreManager.saveOwnAccountAddress(publicKey)
        return null
    }

    companion object {
        const val PHRASE_WORDS = 12
        private const val TAG = "Recovery"
    }
}

private const val STATUS_TIMEOUT_SECONDS = 20L
