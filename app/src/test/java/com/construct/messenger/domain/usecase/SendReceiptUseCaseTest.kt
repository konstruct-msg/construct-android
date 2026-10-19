package com.construct.messenger.domain.usecase

import com.construct.messenger.data.repository.SecuritySettingsRepository
import com.construct.messenger.service.SessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * The delivery-receipt switch (iOS `DeliveryReceiptPreferenceTests`): off, nothing is prepared;
 * on, the receipt goes the usual way — here up to the session lookup, which answers no session.
 */
class SendReceiptUseCaseTest {
    private val sessionManager: SessionManager = mock()
    private val settings: SecuritySettingsRepository = mock()
    private val useCase = SendReceiptUseCase(mock(), sessionManager, mock(), mock(), mock(), mock(), mock(), mock(), settings)

    /** Mutation: drop the check — reddens. */
    @Test
    fun `off, no receipt is prepared`() = runTest {
        whenever(settings.sendsDeliveryReceipts).thenReturn(MutableStateFlow(false))
        useCase.delivered("contact", listOf("m1"))
        verify(sessionManager, never()).resolveTarget(any())
    }

    /** Mutation: always return — reddens. */
    @Test
    fun `on, the receipt goes`() = runTest {
        whenever(settings.sendsDeliveryReceipts).thenReturn(MutableStateFlow(true))
        useCase.delivered("contact", listOf("m1"))
        verify(sessionManager).resolveTarget("contact")
    }
}
