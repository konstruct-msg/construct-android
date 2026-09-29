package com.construct.messenger.stealth

import android.content.Context
import android.content.SharedPreferences
import com.construct.messenger.diagnostics.Log
import com.construct.messenger.data.api.GrpcClient
import com.google.protobuf.ByteString
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton
import shared.proto.services.v1.AuthServiceOuterClass.IssueTokensRequest
import uniffi.construct_core.ppBlindToken
import uniffi.construct_core.ppFinalizeToken
import uniffi.construct_core.ppVerifyClient
import uniffi.construct_core.ppVerifyDleq

/**
 * Privacy Pass token issuance — mirrors iOS `BlindTokenService`.
 *
 * Flow per token: random 32-byte nonce → [ppBlindToken] (blinded point +
 * blind factor) → `IssueTokens` RPC (authenticated — issuance is where the
 * server meters, spending is anonymous) → batched DLEQ against the pinned issuer
 * key ([IssuerKeyPin]) → [ppVerifyClient] sanity check →
 * [ppFinalizeToken] → deposit into [TokenWalletService].
 *
 * Server caps issuance at 20/hr per user and 1–20 points per call; a local
 * 1h cooldown keeps us clear of the limit (same as iOS).
 */
@Singleton
class BlindTokenService @Inject constructor(
    @ApplicationContext context: Context,
    private val grpcClient: GrpcClient,
    private val wallet: TokenWalletService,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)
    private val random = SecureRandom()

    /** First-launch top-up: fill the wallet if empty, ignoring the cooldown. */
    suspend fun bootstrapInitialBatch() {
        if (wallet.balance > 0) return
        replenish(count = DEFAULT_BATCH, respectCooldown = false)
    }

    /**
     * Requests [count] fresh tokens (clamped to 1..20) and deposits them.
     * Returns the number of tokens actually added.
     */
    suspend fun replenish(count: Int = DEFAULT_BATCH, respectCooldown: Boolean = true): Int {
        if (respectCooldown) {
            val last = prefs.getLong(KEY_LAST_REPLENISH, 0L)
            if (System.currentTimeMillis() - last < COOLDOWN_MS) return 0
        }

        val n = count.coerceIn(1, 20)
        val nonces = List(n) { ByteArray(32).also(random::nextBytes) }

        return try {
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
                is IssuerKeyPin.Verdict.Reject -> {
                    Log.e(TAG, "issuance rejected: ${verdict.reason}")
                    return 0
                }
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

            wallet.deposit(tokens)
            prefs.edit().putLong(KEY_LAST_REPLENISH, System.currentTimeMillis()).apply()
            Log.i(TAG, "deposited ${tokens.size} blind tokens (balance=${wallet.balance})")
            tokens.size
        } catch (e: Exception) {
            Log.w(TAG, "token replenishment failed (non-fatal)", e)
            0
        }
    }

    private companion object {
        const val TAG = "BlindTokenService"
        const val PREFS_FILE_NAME = "stealth_blind_token_prefs"
        const val KEY_LAST_REPLENISH = "last_replenish_at"
        const val DEFAULT_BATCH = 15
        const val COOLDOWN_MS = 60 * 60 * 1000L
    }
}

