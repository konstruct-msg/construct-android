package com.construct.messenger.stealth

import com.construct.messenger.diagnostics.Log
import com.construct.messenger.data.api.GrpcClient
import com.google.protobuf.ByteString
import io.grpc.Status
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import shared.proto.services.v1.AuthServiceOuterClass.IssueTokensRequest
import uniffi.construct_core.ppBlindToken
import uniffi.construct_core.ppFinalizeToken
import uniffi.construct_core.ppVerifyClient
import uniffi.construct_core.ppVerifyDleq

/**
 * Privacy Pass token issuance. **Canon:** iOS `BlindTokenService`, whose policy this ports.
 *
 * Flow per token: random 32-byte nonce → [ppBlindToken] (blinded point +
 * blind factor) → `IssueTokens` RPC (authenticated — issuance is where the
 * server meters, spending is anonymous) → batched DLEQ against the pinned issuer
 * key ([IssuerKeyPin]) → [ppVerifyClient] sanity check →
 * [ppFinalizeToken] → deposit into [TokenWalletService].
 *
 * A token goes with every sealed envelope that carries no credential ([StealthPolicy]), so the
 * wallet is a bank kept toward [BANK_TARGET], not a handful pulled once: topped up after every
 * spend, paced by a short pause between successful batches, and held back only when the issuer
 * refuses. The server bounds a call at 20 points and caps issuance per account per hour (30 for a
 * young account, 120 for a mature one); hitting the cap is [Outcome.RATE_LIMITED], which waits
 * for the next UTC hour. Until 2026-10-29 Android pulled 15 tokens on an empty start and otherwise
 * waited an hour after any batch — most of its sealed envelopes went without one.
 */
@Singleton
class BlindTokenService @Inject constructor(
    private val grpcClient: GrpcClient,
    private val wallet: TokenWalletService,
) {
    private val random = SecureRandom()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    @Volatile private var inFlight: Deferred<Unit>? = null
    /** Our own politeness between successful batches. Never holds an empty wallet back. */
    @Volatile private var pacingUntilMs = 0L
    /** The issuer said no. This one holds: asking again inside it cannot succeed. */
    @Volatile private var backoffUntilMs = 0L

    /** The last issuance attempt, for the diagnostic log. */
    @Volatile var lastOutcome: Outcome? = null
        private set

    enum class Outcome { OK, SERVER_DISABLED, RATE_LIMITED, UNAUTHENTICATED, VERIFY_REJECTED, TRANSPORT }

    private val running: Boolean get() = inFlight?.isActive == true

    /**
     * At start and on every reconnect: a cold wallet fills toward the bank in as many batches as
     * it has room for, a low one pulls one batch at the usual pace, a banked one nothing.
     */
    suspend fun bootstrapInitialBatch() {
        val balance = wallet.balance
        when (Replenish.bootstrapAction(balance, running, backoffUntilMs, now())) {
            Replenish.Bootstrap.SKIP -> return
            Replenish.Bootstrap.BANK -> replenish()
            Replenish.Bootstrap.COLD_START -> {
                val batches = Replenish.coldStartBatchCount(balance, BANK_TARGET, BATCH_SIZE)
                Log.i(TAG, "cold start filling toward $BANK_TARGET (wallet=$balance, up to $batches batch(es))")
                repeat(batches) {
                    val before = wallet.balance
                    val room = BANK_TARGET - before
                    if (room <= 0) return
                    pacingUntilMs = 0
                    replenish(min(BATCH_SIZE, room))
                    // A batch that added nothing is the issuer saying the hour is full.
                    if (wallet.balance <= before) return
                }
            }
        }
    }

    /** One batch, unless one is running or the gate holds it. Returns when it is done or skipped. */
    suspend fun replenish(count: Int = BATCH_SIZE) {
        val batch = synchronized(lock) {
            if (Replenish.gate(wallet.balance, running, pacingUntilMs, backoffUntilMs, now()) != Replenish.Gate.START) {
                null
            } else {
                startLocked(count)
            }
        }
        batch?.await()
    }

    /**
     * The server just refused a token: whatever pacing or back-off we hold is stale by
     * definition. Clear both and pull a batch, so the one sealed retry has a token.
     */
    suspend fun forceReplenish() {
        pacingUntilMs = 0
        backoffUntilMs = 0
        replenish()
    }

    /** After a spend: chase the bank without holding up the send. Cheap — the gate decides. */
    fun topUpSoon() {
        if (wallet.balance >= BANK_TARGET) return
        scope.launch { runCatching { replenish() } }
    }

    /**
     * Give a send that needs a token the chance of one, for at most [timeoutMs]. A burst that
     * outran the wallet used to step over a batch already in flight and go without (iOS, 93 of
     * 271 in one hour on 2026-08-04). Never waits on a back-off: the issuer already answered.
     */
    suspend fun ensureTokenAvailable(timeoutMs: Long = TOKEN_WAIT_MS): Boolean {
        if (wallet.balance > 0) return true
        val batch = synchronized(lock) {
            when (Replenish.gate(wallet.balance, running, pacingUntilMs, backoffUntilMs, now())) {
                Replenish.Gate.WAIT_FOR_IN_FLIGHT -> inFlight
                Replenish.Gate.START -> startLocked(BATCH_SIZE)
                // Pacing is unreachable with an empty wallet; kept apart so a change to that rule
                // shows here rather than reinstating a wait.
                Replenish.Gate.BLOCKED_BY_BACKOFF, Replenish.Gate.BLOCKED_BY_PACING -> null
            }
        } ?: return false
        withTimeoutOrNull(timeoutMs) { batch.await() }
        return wallet.balance > 0
    }

    private fun startLocked(count: Int): Deferred<Unit> =
        scope.async { performBatch(count.coerceIn(1, BATCH_SIZE)) }.also { inFlight = it }

    private suspend fun performBatch(n: Int) {
        try {
            val tokens = issue(n)
            wallet.deposit(tokens)
            lastOutcome = Outcome.OK
            pacingUntilMs = now() + SUCCESS_PACING_MS
            backoffUntilMs = 0
            Log.i(TAG, "deposited ${tokens.size} blind tokens (balance=${wallet.balance})")
        } catch (e: Exception) {
            val outcome = Replenish.classify(e)
            lastOutcome = outcome
            backoffUntilMs = Replenish.backoffUntil(outcome, now())
            Log.w(TAG, "token replenishment failed [$outcome] (non-fatal)", e)
        }
    }

    /** One `IssueTokens` call: [n] tokens finalized, or a throw [Replenish.classify] can name. */
    private suspend fun issue(n: Int): List<BlindToken> {
        val nonces = List(n) { ByteArray(32).also(random::nextBytes) }

        // ppBlindToken returns blinded_point(32) || blind_factor(32).
        val blinded = nonces.map { ppBlindToken(it) }

        val request = IssueTokensRequest.newBuilder()
            .addAllBlindedPoints(blinded.map { ByteString.copyFrom(it, 0, 32) })
            .build()
        val response = grpcClient.auth.issueTokens(request)

        val serverPubkey = response.serverPubkey.toByteArray()
        val verdict = IssuerKeyPin.check(
            requested = n,
            blinded = blinded.map { it.copyOfRange(0, 32) },
            evaluated = response.evaluatedPointsList.map { it.toByteArray() },
            serverPubkey = serverPubkey,
            dleqProof = response.dleqProof.toByteArray(),
            keyVersion = response.issuerKeyVersion,
        ) { b, e, proof, pin ->
            ppVerifyDleq(b.map { it }, e.map { it }, proof, pin)
        }
        val issued = when (verdict) {
            is IssuerKeyPin.Verdict.Reject -> throw IssuanceRejected("issuer key: ${verdict.reason}")
            is IssuerKeyPin.Verdict.Accept -> {
                if (verdict.dleqChecked) Log.i(TAG, "DLEQ verified against pinned issuer key v${response.issuerKeyVersion} (${verdict.issued} pts)")
                verdict.issued
            }
        }

        val tokens = nonces.take(issued).mapIndexedNotNull { i, nonce ->
            val evaluated = response.getEvaluatedPoints(i).toByteArray()
            if (!ppVerifyClient(evaluated, nonce, serverPubkey)) {
                Log.w(TAG, "evaluated point $i failed client verification — skipping")
                return@mapIndexedNotNull null
            }
            val blindFactor = blinded[i].copyOfRange(32, 64)
            val token = ppFinalizeToken(
                evaluated,
                blindFactor,
                nonce,
            )
            BlindToken(nonce = nonce, token = token)
        }
        if (tokens.isEmpty()) throw IssuanceRejected("every evaluated point failed client verification")
        return tokens
    }

    internal class IssuanceRejected(message: String) : Exception(message)

    /** The decisions, pure, so a test can argue with them. **Canon:** iOS `ReplenishGate`, `BootstrapAction`. */
    internal object Replenish {
        enum class Gate { START, WAIT_FOR_IN_FLIGHT, BLOCKED_BY_BACKOFF, BLOCKED_BY_PACING }
        enum class Bootstrap { SKIP, COLD_START, BANK }

        /** What a send, or a top-up, may do right now. An empty wallet outranks our own pacing. */
        fun gate(balance: Int, running: Boolean, pacingUntilMs: Long, backoffUntilMs: Long, nowMs: Long): Gate = when {
            running -> Gate.WAIT_FOR_IN_FLIGHT
            backoffUntilMs > nowMs -> Gate.BLOCKED_BY_BACKOFF
            balance == 0 -> Gate.START
            pacingUntilMs > nowMs -> Gate.BLOCKED_BY_PACING
            else -> Gate.START
        }

        fun bootstrapAction(balance: Int, running: Boolean, backoffUntilMs: Long, nowMs: Long): Bootstrap = when {
            running || balance >= BANK_TARGET || backoffUntilMs > nowMs -> Bootstrap.SKIP
            balance < COLD_START_MARK -> Bootstrap.COLD_START
            else -> Bootstrap.BANK
        }

        /** How many batches a cold wallet may pull at once: as many as the bank has room for. */
        fun coldStartBatchCount(balance: Int, bankTarget: Int, batchSize: Int): Int {
            if (batchSize <= 0 || balance >= bankTarget) return 0
            return (bankTarget - balance + batchSize - 1) / batchSize
        }

        /**
         * When to ask again after a refusal. The hourly cap belongs to the UTC hour the issuer
         * counted, so it waits for the next one, not a flat hour; a blip waits two minutes; a
         * disabled issuer or a bad key an hour.
         */
        fun backoffUntil(outcome: Outcome, nowMs: Long): Long = when (outcome) {
            Outcome.OK -> 0
            Outcome.TRANSPORT, Outcome.UNAUTHENTICATED -> nowMs + TRANSIENT_RETRY_MS
            Outcome.RATE_LIMITED -> nextUtcHour(nowMs)
            Outcome.SERVER_DISABLED, Outcome.VERIFY_REJECTED -> nowMs + REFUSAL_BACKOFF_MS
        }

        /** Strictly after [nowMs]: a refusal on the boundary belongs to the hour that just opened. */
        fun nextUtcHour(nowMs: Long): Long = (nowMs / HOUR_MS + 1) * HOUR_MS

        fun classify(error: Throwable): Outcome {
            if (error is IssuanceRejected) return Outcome.VERIFY_REJECTED
            val status = Status.fromThrowable(error)
            return when (status.code) {
                Status.Code.RESOURCE_EXHAUSTED -> Outcome.RATE_LIMITED
                Status.Code.UNAUTHENTICATED -> Outcome.UNAUTHENTICATED
                Status.Code.UNAVAILABLE -> {
                    val m = status.description.orEmpty().lowercase()
                    if ("not configured" in m || "token issuance" in m) Outcome.SERVER_DISABLED else Outcome.TRANSPORT
                }
                else -> Outcome.TRANSPORT
            }
        }
    }

    private fun now() = System.currentTimeMillis()

    internal companion object {
        const val TAG = "BlindTokenService"
        /** The server bounds an `IssueTokens` call at 20 points. */
        const val BATCH_SIZE = 20
        /** One hour of a mature account's issuance, banked ahead of demand (iOS `bankTarget`). */
        const val BANK_TARGET = 120
        /** A wallet this shallow is a cold start: the one fill that may skip pacing. */
        const val COLD_START_MARK = 10
        const val SUCCESS_PACING_MS = 90_000L
        const val TRANSIENT_RETRY_MS = 120_000L
        const val REFUSAL_BACKOFF_MS = 3_600_000L
        const val HOUR_MS = 3_600_000L
        /** Paid at most once per batch; under enforce a token-less send costs a refusal and a retry. */
        const val TOKEN_WAIT_MS = 2_000L
    }
}
