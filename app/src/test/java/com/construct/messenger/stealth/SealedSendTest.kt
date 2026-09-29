package com.construct.messenger.stealth

import com.construct.messenger.data.api.MessagingService
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class SealedSendTest {

    @Test
    fun `a credential pays, unless the server just refused one, and then a token does`() {
        assertEquals(EnvelopePayment.CREDENTIAL, EnvelopePayment.choose(credential = true, afterCredentialRejection = false, policyWantsToken = true))
        assertEquals(EnvelopePayment.TOKEN, EnvelopePayment.choose(credential = false, afterCredentialRejection = false, policyWantsToken = true))
        assertEquals(EnvelopePayment.NOTHING, EnvelopePayment.choose(credential = false, afterCredentialRejection = false, policyWantsToken = false))
        // Mutation: offer the credential again after a refusal — the loop iOS measured 2026-09-14.
        assertEquals(EnvelopePayment.TOKEN, EnvelopePayment.choose(credential = true, afterCredentialRejection = true, policyWantsToken = false))
    }

    @Test
    fun `only the server's privacy pass refusal is recognised`() {
        assertEquals("missing_token", SealedSend.rejectionLabel(refusal("privacy_pass:missing_token")))
        assertNull(SealedSend.rejectionLabel(StatusException(Status.FAILED_PRECONDITION.withDescription("other"))))
        assertNull(SealedSend.rejectionLabel(StatusException(Status.UNAVAILABLE.withDescription("privacy_pass:x"))))
    }

    private val stealth = mock<StealthSenderService>()
    private val messaging = mock<MessagingService>()
    private val tokens = mock<BlindTokenService>()
    private val send = SealedSend(stealth, messaging, tokens)
    private val ok = MessagingService.SendResult("id", true, "", false, 0, "a")

    /** Mutation: retry without afterCredentialRejection — the same credential is refused again. */
    @Test
    fun `a refusal tops up, rebuilds paying, and sends once more — sealed`() = runTest {
        whenever(stealth.buildSealedInner(any(), any(), any(), any(), eq(false))).thenReturn(byteArrayOf(1))
        whenever(stealth.buildSealedInner(any(), any(), any(), any(), eq(true))).thenReturn(byteArrayOf(2))
        whenever(messaging.sendSealedMessage(any())).thenAnswer {
            if ((it.arguments[0] as ByteArray)[0] == 1.toByte()) throw refusal("privacy_pass:missing_token") else ok
        }

        val result = send.send("peer", ByteArray(32), byteArrayOf(9), SealedEnvelopeType.GENERIC)

        assertTrue(result.success)
        inOrder(tokens, stealth) {
            verify(tokens).replenish(any(), eq(false))
            verify(stealth).buildSealedInner(any(), any(), any(), any(), eq(true))
        }
        verify(messaging, never()).sendMessage(any(), any(), any(), any(), any(), any(), anyOrNull())
    }

    @Test
    fun `any other error is the caller's`() = runTest {
        whenever(stealth.buildSealedInner(any(), any(), any(), any(), any())).thenReturn(byteArrayOf(1))
        whenever(messaging.sendSealedMessage(any())).thenAnswer { throw StatusException(Status.UNAVAILABLE) }

        val thrown = runCatching { send.send("peer", ByteArray(32), byteArrayOf(9), SealedEnvelopeType.GENERIC) }.exceptionOrNull()

        assertTrue(thrown is StatusException)
        verify(tokens, never()).replenish(any(), any())
    }

    private fun refusal(description: String) = StatusException(Status.FAILED_PRECONDITION.withDescription(description))
}
