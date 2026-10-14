package com.construct.messenger.ui

import android.system.ErrnoException
import android.system.OsConstants
import androidx.annotation.StringRes
import com.construct.messenger.R
import com.construct.messenger.data.repository.ServerRefusedException
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import java.io.IOException

/**
 * What a person sees when something fails: one of a closed set of localized texts, picked by the
 * exception's type and code — never by its message. An exception's message, a gRPC status
 * description and a server `reason` go to the log only (they can carry a relay's address, a
 * path, a stack of English).
 *
 * Callers rethrow `CancellationException` before they get here.
 */
enum class UserError(@StringRes val text: Int) {
    NO_CONNECTION(R.string.error_no_connection),
    SERVER_FAILED(R.string.error_server_failed),
    SIGN_IN_AGAIN(R.string.error_sign_in_again),
    TOO_MANY_ATTEMPTS(R.string.error_too_many_attempts),
    NO_SPACE(R.string.error_no_space),
    SOMETHING_WENT_WRONG(R.string.error_something_went_wrong),
    ;

    companion object {
        private const val MAX_CHAIN = 12

        fun from(error: Throwable): UserError {
            val chain = generateSequence(error) { it.cause }.take(MAX_CHAIN).toList()
            // Disk first: an out-of-space write is an IOException, but not a network one.
            if (chain.any { it is ErrnoException && it.errno == OsConstants.ENOSPC }) return NO_SPACE
            if (chain.any { it is ServerRefusedException }) return SERVER_FAILED
            chain.firstNotNullOfOrNull(::grpcCode)?.let { return fromCode(it) }
            if (chain.any { it is IOException }) return NO_CONNECTION
            return SOMETHING_WENT_WRONG
        }

        private fun grpcCode(t: Throwable): Status.Code? = when (t) {
            is StatusRuntimeException -> t.status.code
            is StatusException -> t.status.code
            else -> null
        }

        private fun fromCode(code: Status.Code): UserError = when (code) {
            Status.Code.UNAVAILABLE, Status.Code.DEADLINE_EXCEEDED, Status.Code.CANCELLED -> NO_CONNECTION
            Status.Code.UNAUTHENTICATED -> SIGN_IN_AGAIN
            Status.Code.RESOURCE_EXHAUSTED -> TOO_MANY_ATTEMPTS
            else -> SERVER_FAILED
        }
    }
}
