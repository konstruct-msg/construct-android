package com.construct.messenger.domain.usecase

import com.construct.messenger.R
import com.construct.messenger.viewmodel.RestoreAccountViewModel
import io.grpc.Status
import io.grpc.StatusException
import org.junit.Assert.assertEquals
import org.junit.Test

class RecoverAccountTest {

    /** The phrase is checked and derived in the form it was generated in. */
    @Test
    fun `a phrase typed with capitals and extra spaces is the generated phrase`() {
        assertEquals("abandon ability able", normalizePhrase("  Abandon   ABILITY\table \n"))
    }

    @Test
    fun `each refusal says what to fix`() {
        assertEquals(R.string.recovery_confirm_invalid_phrase, RestoreAccountViewModel.errorFor(RecoverRefused(RecoverRefusal.INVALID_PHRASE)))
        assertEquals(R.string.restore_error_identifier, RestoreAccountViewModel.errorFor(RecoverRefused(RecoverRefusal.NO_IDENTIFIER)))
        assertEquals(R.string.restore_error_rejected, RestoreAccountViewModel.errorFor(StatusException(Status.NOT_FOUND)))
        assertEquals(R.string.restore_error_rejected, RestoreAccountViewModel.errorFor(StatusException(Status.UNAUTHENTICATED)))
        assertEquals(R.string.restore_error_rate_limited, RestoreAccountViewModel.errorFor(StatusException(Status.RESOURCE_EXHAUSTED)))
        assertEquals(R.string.restore_error_network, RestoreAccountViewModel.errorFor(StatusException(Status.UNAVAILABLE)))
    }
}
