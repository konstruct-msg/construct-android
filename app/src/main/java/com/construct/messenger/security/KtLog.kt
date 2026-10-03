package com.construct.messenger.security

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Every Key Transparency verdict this device reached, as a tally for Settings → Security.
 * **Canon:** iOS `KTStore` — after [SUCCESSES_TO_CLEAR] verifications in a row the failures are
 * cleared, so a passing outage does not leave the screen red for good.
 */
data class KtTally(
    val verified: Int = 0,
    val failures: Int = 0,
    val consecutiveSuccesses: Int = 0,
    val lastFailedAtMs: Long? = null,
) {
    fun afterVerified(): KtTally {
        val run = consecutiveSuccesses + 1
        return if (run >= SUCCESSES_TO_CLEAR && failures > 0) {
            copy(verified = verified + 1, failures = 0, consecutiveSuccesses = 0)
        } else {
            copy(verified = verified + 1, consecutiveSuccesses = run)
        }
    }

    fun afterFailure(nowMs: Long): KtTally = copy(failures = failures + 1, consecutiveSuccesses = 0, lastFailedAtMs = nowMs)

    companion object {
        const val SUCCESSES_TO_CLEAR = 3
    }
}

@Singleton
class KtLog @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("kt_log", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())
    val tally: StateFlow<KtTally> = state.asStateFlow()

    fun recordVerified() = write(state.value.afterVerified())

    fun recordFailure() = write(state.value.afterFailure(System.currentTimeMillis()))

    private fun read() = KtTally(
        verified = prefs.getInt(VERIFIED, 0),
        failures = prefs.getInt(FAILURES, 0),
        consecutiveSuccesses = prefs.getInt(RUN, 0),
        lastFailedAtMs = prefs.getLong(LAST_FAILED, 0L).takeIf { it > 0 },
    )

    @Synchronized
    private fun write(next: KtTally) {
        prefs.edit()
            .putInt(VERIFIED, next.verified)
            .putInt(FAILURES, next.failures)
            .putInt(RUN, next.consecutiveSuccesses)
            .putLong(LAST_FAILED, next.lastFailedAtMs ?: 0L)
            .apply()
        state.value = next
    }

    private companion object {
        const val VERIFIED = "verified"
        const val FAILURES = "failures"
        const val RUN = "consecutive_successes"
        const val LAST_FAILED = "last_failed_at_ms"
    }
}
