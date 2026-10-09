package com.construct.messenger.security

import com.construct.messenger.data.local.ContactStore
import com.construct.messenger.data.local.ContactRecord
import com.construct.messenger.data.model.ContactTrustAlert
import com.construct.messenger.data.model.KtStatus
import com.construct.messenger.data.model.SecurityNotice
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import uniffi.construct_core.KtVerdict

/** KT on Android (TODO 108 step 5): the core's verdict, what the contact shows, and the tally. */
class KtRulesTest {

    /** Mutation: record UNAVAILABLE as a failure — a server that sends no proof turns every contact red. */
    @Test
    fun `only a judged proof changes the contact`() {
        assertEquals(KtStatus.VERIFIED, KtCheck.statusOf(KtVerdict.VERIFIED))
        assertNull(KtCheck.statusOf(KtVerdict.UNAVAILABLE))
        assertNull(KtCheck.statusOf(null))
        for (bad in listOf(KtVerdict.MALFORMED_PROOF, KtVerdict.INCLUSION_PROOF_INVALID, KtVerdict.SIGNATURE_INVALID)) {
            assertEquals(KtStatus.FAILED, KtCheck.statusOf(bad))
        }
    }

    @Test
    fun `an event outranks a failed proof, and a verified one warns of nothing`() {
        assertEquals(ContactTrustAlert.ADDRESS_CHANGED, ContactTrustAlert.of(SecurityNotice.ADDRESS_CHANGED, KtStatus.FAILED))
        assertEquals(ContactTrustAlert.VERIFICATION_FAILED, ContactTrustAlert.of(SecurityNotice.NONE, KtStatus.FAILED))
        assertNull(ContactTrustAlert.of(SecurityNotice.NONE, KtStatus.VERIFIED))
        assertNull(ContactTrustAlert.of(SecurityNotice.NONE, KtStatus.UNVERIFIED))
    }

    /** The codes are iOS `KTStatus`'s. */
    @Test
    fun `status codes match iOS`() {
        assertEquals(listOf(0, 1, 3), KtStatus.entries.map { it.code })
        assertEquals(KtStatus.UNVERIFIED, KtStatus.of(2))
    }

    @Test
    fun `three verifications in a row clear the failures`() {
        var t = KtTally().afterFailure(nowMs = 5)
        assertEquals(1, t.failures)
        t = t.afterVerified().afterVerified()
        assertEquals(1, t.failures)
        t = t.afterVerified()
        assertEquals(0, t.failures)
        assertEquals(3, t.verified)
        t = t.afterFailure(9).afterVerified().afterFailure(10)
        assertEquals(2, t.failures)
        assertEquals(10L, t.lastFailedAtMs)
    }

    /**
     * Acknowledging a failed proof makes it "not verified", never verified (iOS writes verified,
     * which shows the mark for a proof that never passed). Mutation: write VERIFIED — reddens.
     */
    @Test
    fun `acknowledging a failed proof does not mark it verified`() = runBlocking {
        val dao = mock<ContactStore>()
        whenever(dao.get("u")).thenReturn(ContactRecord(id = "u", ktStatus = KtStatus.FAILED.code))
        SecurityNotices(dao, mock()).acknowledge("u")
        verify(dao).setSecurityNotice("u", SecurityNotice.NONE.code)
        verify(dao).setKtStatus("u", KtStatus.UNVERIFIED.code)

        val verified = mock<ContactStore>()
        whenever(verified.get("v")).thenReturn(ContactRecord(id = "v", ktStatus = KtStatus.VERIFIED.code))
        SecurityNotices(verified, mock()).acknowledge("v")
        verify(verified, never()).setKtStatus(any(), any())
    }
}
